package org.apache.shardingsphere.shardingspheredemo.repository;


import org.apache.shardingsphere.shardingspheredemo.entity.User;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository {

    void addEntity(User user);

    List<User> findAll();

}
