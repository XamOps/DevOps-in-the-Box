package in.xammer.aws_cost_api;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class AwsCostApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AwsCostApiApplication.class, args);
	}

}
