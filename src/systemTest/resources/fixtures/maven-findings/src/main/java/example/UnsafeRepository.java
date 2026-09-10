package example;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
interface UnsafeRepository {
  @Query("SELECT u FROM User u " + "WHERE u.active = true")
  List<Object> findAllActive();
}
