package org.apache.shardingsphere.shardingspheredemo.entity;

import lombok.Data;

@Data
public class HealthRecord {
    private Long recordId;
    private Integer userId;
    private Integer levelId;
    private String remark;
}
