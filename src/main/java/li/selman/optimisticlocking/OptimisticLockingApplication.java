package li.selman.optimisticlocking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
// For stable Pageable DTO
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class OptimisticLockingApplication {

    public static void main(String[] args) {
        SpringApplication.run(OptimisticLockingApplication.class, args);
    }

}
