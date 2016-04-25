/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.csiro.flower.service;

import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
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
public class DynamoCtrlServiceImpl implements DynamoCtrlService {

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

    ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);

    @Override
    public void initService(String provider, String accessKey, String secretKey, String region) {
        cloudWatchService.initService(provider, accessKey, secretKey, region);
        dynamoMgmtService.initService(provider, accessKey, secretKey, region);
    }

    @Override
    public void startDynamoCtrl(final String tblName, final String measurementTarget, 
            final double refValue, int schedulingPeriod, final int backoffNo) {

        dynamoCtrlGainQ = new LinkedList<>();

        final Runnable runMonitorAndControl = new Runnable() {
            @Override
            public void run() {
                runDynamoController(tblName, measurementTarget, refValue, backoffNo);
//                System.out.println("Dynamo Write Rate: " + writeRate);
                // System.out.println(Thread.currentThread().getName());
            }
        };
        scheduledThreadPool.scheduleAtFixedRate(runMonitorAndControl, 0, schedulingPeriod, TimeUnit.MINUTES);
    }

    private void runDynamoController(String tblName, String measurementTarget,
            double writeUtilizationRef, int initBackoff) {
        double error;
//        double threshold = 30;
        double k0;
        double uk0;
        double uk1;
//        double writeUtilizationRef = 70;
        double writeUtilizationPercent;
        int roundedUk1;
        boolean decisionRevoked = true;
        int backoffNo = initBackoff;

        /*
         e.g. 10 units of Write Capacity is enough to do up to 36,000 writes per hour
         Units of Capacity required for writes = Number of item writes per second x item size in 1KB blocks
         Units of Capacity required for reads* = Number of item reads per second x item size in 4KB blocks 
         */
        double writeRate = getDynamoStats(tblName,measurementTarget);
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

//        ctrlMConitor.pushCtrlParams("Dynamo_Ctrl_Stats", uk1, roundedUk1, k0, error, backoffNo, uk0, writeRate);
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
                getCriticalResourceStats("DynamoDB", tblName, measurementTarget, twoMinMil);
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

}
