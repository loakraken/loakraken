package com.loakraken;

import org.springframework.boot.SpringApplication;

public class TestLoakrakenApplication {

    public static void main(String[] args) {
        SpringApplication.from(LoakrakenApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
