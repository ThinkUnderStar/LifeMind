package thinkunderstar.lifemind.lifemindaccount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LifeMindAccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeMindAccountApplication.class, args);
    }

}
