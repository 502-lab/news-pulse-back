package com.newscurator;

import com.newscurator.config.CorsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CorsProperties.class)
public class NewsCuratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(NewsCuratorApplication.class, args);
  }
}
