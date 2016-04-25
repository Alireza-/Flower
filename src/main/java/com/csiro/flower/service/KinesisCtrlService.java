/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.csiro.flower.service;

/**
 *
 * @author kho01f
 */
public interface KinesisCtrlService {

    public void initService(String provider, String accessKey, String secretKey, String region);

    public void startKinesisCtrl(final String streamName, final String measurementTarget,
            final double putRecordUtilizationRef, int schedulingPeriod, final int backoffNo);

}
