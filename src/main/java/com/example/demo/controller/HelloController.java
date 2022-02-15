package com.example.demo.controller;

import com.example.demo.bean.Car;
import com.example.demo.common.LettuceCommand;
import com.example.demo.mapper.primary.CarMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Created by guiqi on 2017/7/28.
 */

@RestController
public class HelloController {

    @Autowired
    private CarMapper carMapper;

    @Autowired
    private LettuceCommand lettuceCommand;

    @RequestMapping("/")
    public String index() {

        Car car =  carMapper.search("别摸我",null).iterator().next();
        return "Greetings from Spring Boot!" + car;
    }

    @GetMapping("/setnx/{key}/{val}")
    public boolean setnx(@PathVariable String key, @PathVariable String val) {
        return lettuceCommand.setnx(key, val);
    }


    @GetMapping("/delnx/{key}/{val}")
    public int delnx(@PathVariable String key, @PathVariable String val) {
        return lettuceCommand.delnx(key, val);
    }


}
