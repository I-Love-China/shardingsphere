package org.apache.shardingsphere.shardingspheredemo.service;

import org.apache.shardingsphere.shardingspheredemo.ShardingsphereDemoApplication;
import org.apache.shardingsphere.shardingspheredemo.entity.HealthRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * @author: zhangjl
 * @date: 2022/9/10
 * @description:
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = ShardingsphereDemoApplication.class)
public class HealthRecordServiceTest {
    @Autowired
    private HealthRecordService healthRecordService;

    @Test
    public void test() {
        healthRecordService.processHealthRecords();
        List<HealthRecord> healthRecords = healthRecordService.queryAll();
        System.out.println(healthRecords);
    }

}