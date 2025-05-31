// (Remove the package declaration if the file is not in the correct directory)
package in.xammer.aws_cost_api;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class AwsCostApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AwsCostApiApplication.class, args);
	}

}
