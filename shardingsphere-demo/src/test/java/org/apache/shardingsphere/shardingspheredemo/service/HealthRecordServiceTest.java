package org.apache.shardingsphere.shardingspheredemo.service;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.shardingspheredemo.ShardingsphereDemoApplication;
import org.apache.shardingsphere.shardingspheredemo.entity.HealthRecord;
import org.apache.shardingsphere.shardingspheredemo.repository.HealthRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private HealthRecordRepository healthRecordRepository;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
//    @Transactional
    public void test() {
//        healthRecordService.processHealthRecords();
        List<HealthRecord> healthRecords = healthRecordService.queryAll();
        System.out.println(healthRecords);
    }

    @Test
    public void testUpdateShardingKey() {
        // 777673759166300160L
        healthRecordRepository.updateRecordId(777673759166300160L, 777673759166300161L);
        System.out.println(1);
    }

}