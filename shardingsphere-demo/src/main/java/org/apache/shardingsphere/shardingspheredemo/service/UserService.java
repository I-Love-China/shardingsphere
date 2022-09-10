package org.apache.shardingsphere.shardingspheredemo.service;


import org.apache.shardingsphere.shardingspheredemo.entity.User;
import org.apache.shardingsphere.shardingspheredemo.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(
            UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void processUsers() {
        insertUsers();
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    private void insertUsers() {
        for (int i = 0; i <= 5; i++) {
            insertUser(i);
        }
    }

    private void insertUser(int i) {
        User user = new User();
        user.setUserId(i);
        user.setUserName("userName" + i);
        userRepository.addEntity(user);
    }

}
