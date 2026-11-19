package com.papazone.order;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
@EnableDiscoveryClient
@EnableScheduling
@SpringBootApplication
public class Application { public static void main(String[] args) { SpringApplication.run(Application.class,args); } }
