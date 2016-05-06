/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.csiro.flower.service;

import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.csiro.flower.dao.DynamoCtrlDao;
import com.csiro.flower.model.CloudSetting;
import com.csiro.flower.model.DynamoCtrl;
import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 *
 * @author kho01f
 */
@Service
public class DynamoCtrlServiceImpl extends CtrlService {
    
    @Autowired
    DynamoCtrlDao dynamoCtrlDao;
    
    @Autowired
    CloudWatchService cloudWatchService;
    
    @Autowired
    DynamoMgmtService dynamoMgmtService;
    
    final int twoMinMil = 1000 * 60 * 2;
    final int twoMinSec = 120;
    
    double epsilon = 0.0001;
    double upperK0 = 0.1;
    double upInitK0 = 0.08;
    double lowInitK0 = 0.02;
    double lowerK0 = 0;
    double k_init = 0.03;
    double gamma = 0.0003;
    
    Queue dynamoCtrlGainQ;
    String ctrlName = "DynamoDB";
    
    ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);
    
    public long startDynamoConroller(CloudSetting cloudSetting, DynamoCtrl dynamoCtrl) {
        
        initService(
                cloudSetting.getCloudProvider(),
                cloudSetting.getAccessKey(),
                cloudSetting.getSecretKey(),
                cloudSetting.getRegion());
        startDynamoCtrl(
                dynamoCtrl.getTableName(),
                dynamoCtrl.getMeasurementTarget(),
                dynamoCtrl.getRefValue(),
                dynamoCtrl.getMonitoringPeriod(),
                dynamoCtrl.getBackoffNo());
        
        setFlowId(dynamoCtrl.getFlowIdFk());
        setResourceName(dynamoCtrl.getTableName());
        
        return getCtrlThreadId();
    }
    
    private void initService(String provider, String accessKey, String secretKey, String region) {
        cloudWatchService.initService(provider, accessKey, secretKey, region);
        dynamoMgmtService.initService(provider, accessKey, secretKey, region);
    }
    
    private void startDynamoCtrl(final String tblName, final String measurementTarget,
            final double refValue, int schedulingPeriod, final int backoffNo) {
        
        dynamoCtrlGainQ = new LinkedList<>();
        
        final Runnable runMonitorAndControl = new Runnable() {
            @Override
            public void run() {
                setCtrlThreadId(Thread.currentThread().getId());
                runController(tblName, measurementTarget, refValue, backoffNo);
            }
        };
        scheduledThreadPool.scheduleAtFixedRate(runMonitorAndControl, 0, schedulingPeriod, TimeUnit.MINUTES);
    }
    
    private void runController(String tblName, String measurementTarget,
            double writeUtilizationRef, int initBackoff) {
        double error;
        double k0;
        double uk0;
        double uk1;
        double writeUtilizationPercent;
        int roundedUk1;
        boolean decisionRevoked = true;
        int backoffNo = initBackoff;

        /*
         e.g. 10 units of Write Capacity is enough to do up to 36,000 writes per hour
         Units of Capacity required for writes = Number of item writes per second x item size in 1KB blocks
         Units of Capacity required for reads* = Number of item reads per second x item size in 4KB blocks 
         */
        double writeRate = getDynamoStats(tblName, measurementTarget);
        uk0 = dynamoMgmtService.getProvisionedThroughput(tblName).getWriteCapacityUnits();
        writeUtilizationPercent = (writeRate / uk0) * 100;
        
        if (dynamoCtrlGainQ.isEmpty()) {
            k0 = k_init;
        } else {
            k0 = (double) dynamoCtrlGainQ.poll();
            if (k0 >= upperK0) {
                k0 = upInitK0;
            }
            if (k0 <= lowerK0) {
                k0 = lowInitK0;
            }
        }
        
        error = (writeUtilizationPercent - writeUtilizationRef);
        uk1 = uk0 + k0 * error;
        roundedUk1 = (int) Math.round(Math.abs(uk1));
        
        saveMonitoringStats(error, new Timestamp(new Date().getTime()), k0,
                writeRate, uk0, uk1, roundedUk1);

        // If clouadwatch datapoint is null for current period, do not update gains and ProvisionedThroughput!
        if (writeRate != 0) {
            if (((uk1 > uk0)) || ((uk1 < uk0) && /*(Math.abs(error) >= threshold) && */ (backoffNo == 0))) {
                if ((uk1 <= 0) || (roundedUk1 == 0)) {
                    roundedUk1 = 1;
                }
                dynamoCtrlGainQ.add(k0 + gamma * error);
//                dynamoCtrlGainQ.add(Math.abs(k0 + (lambda * ((writeRate / writeUtilizationRef) * 100))));
                decisionRevoked = false;
                //if something really change, go ahead
                if (roundedUk1 != uk0) {
                    dynamoMgmtService.updateProvisionedThroughput(tblName,
                            dynamoMgmtService.getProvisionedThroughput(tblName).getReadCapacityUnits(),
                            roundedUk1);
                }
            }
            
            if ((uk1 < uk0) /*&& (Math.abs(error) >= threshold)*/ && (backoffNo != 0)) {
                backoffNo = backoffNo - 1;
            }

            // Reset backoffNo if the workload reduction is not consecutive 
            // OR we observe increasing workload.
            if (/*((uk1 < uk0) && (Math.abs(error) < threshold)) || */(uk1 > uk0)) {
                backoffNo = initBackoff;
            }
            
        }
        // If Ctrl descision revoked, do not update the gain
        if (decisionRevoked) {
            dynamoCtrlGainQ.add(Math.abs(k0));
        }
    }
    
    public double getDynamoStats(String tblName, String measurementTarget) {
        
        GetMetricStatisticsResult statsResult = cloudWatchService.
                getCriticalResourceStats(ctrlName, tblName, measurementTarget, twoMinMil);
        return getAvgConsumedWriteCapacity(statsResult);
    }
    
    public double getAvgConsumedWriteCapacity(GetMetricStatisticsResult result) {
        double val = 0;
        if (!result.getDatapoints().isEmpty()) {
            for (Datapoint dataPoint : result.getDatapoints()) {
                val += dataPoint.getSum();
            }
            return (val / twoMinSec);
        } else {
            return 0;
        }
    }
    
    @Override
    public void updateCtrlStatus(String ctrlStatus, long threadId, Timestamp date) {
        int ctrlId = dynamoCtrlDao.getPkId(getFlowId(), getResourceName());
        ctrlStatsDao.saveCtrlStatus(ctrlId, ctrlName, ctrlStatus, threadId, date);
    }
    
    private void saveMonitoringStats(double error, Timestamp timestamp,
            double k0, double writeRate, double uk0, double uk1, int roundedUk1) {
        
        int ctrlId = dynamoCtrlDao.getPkId(getFlowId(), getResourceName());
        ctrlStatsDao.saveCtrlMonitoringStats(ctrlId, ctrlName, error, timestamp,
                k0, writeRate, uk0, uk1, roundedUk1);
    }
    
}
