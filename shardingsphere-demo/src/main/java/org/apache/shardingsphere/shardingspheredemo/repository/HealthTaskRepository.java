package org.apache.shardingsphere.shardingspheredemo.repository;


import org.apache.shardingsphere.shardingspheredemo.entity.HealthTask;
import org.springframework.stereotype.Repository;

@Repository
public interface HealthTaskRepository {

    void addEntity(HealthTask healthTask);

}
