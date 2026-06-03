package com.newscurator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NewsCuratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(NewsCuratorApplication.class, args);
  }
}
