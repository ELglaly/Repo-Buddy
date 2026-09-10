package example;
import org.springframework.cache.annotation.Cacheable;
class CacheService {
  @Cacheable("users") Object cached(String id) { return id; }
  Object outer(String id) { return cached(id); }
}
