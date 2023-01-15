package org.apache.shardingsphere.shardingspheredemo.repository;


import org.apache.ibatis.annotations.Param;
import org.apache.shardingsphere.shardingspheredemo.entity.HealthRecord;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthRecordRepository {

    void addEntity(HealthRecord healthRecord);

    List<HealthRecord> findAll();

    HealthRecord queryById(long recordId);

    int updateRecordId(@Param("oldId") long oldId, @Param("newId") long newId);
}
