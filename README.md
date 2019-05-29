# mp-jee-testing

# Goals
1. Simple to setup
1. Work with any JavaEE or MicroProfile runtime
1. Integration tests (for true-to-production tests), but easy to write and fast to run

# How to run locally:

```
./gradlew test
```

# Proposed mockup:
```java
import org.aguibert.testcontainers.framework.jupiter.MicroProfileTest;
import org.aguibert.testcontainers.framework.jupiter.SharedContainerConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@MicroProfileTest
public class BasicJAXRSServiceTest {

    @Container // (1)
    public static MicroProfileApplication app = new MicroProfileApplication()
                    .withAppContextRoot("/myservice");

    @Inject // (2)
    public static PersonService personSvc;

    @Test
    public void testGetPerson() {
        Long bobId = personSvc.createPerson("Bob", 24);
        Person bob = personSvc.getPerson(bobId); // (3)
        assertEquals("Bob", bob.name);
        assertEquals(24, bob.age);
        assertNotNull(bob.id);
    }

}
```

### Explanation of mockup
1. Extend Testcontainers with a `MicroProfileApplication` class that can work
for any JEE/MP implementation. By annotating with `@Container`, Testcontainers 
will automatically find/build the Dockerfile in this project and start it, then
wait for the application context root to be ready.
2. Use the `@Inject` annotation to create a REST Client proxy of the `PersonService`
class which is being tested. This is basically a convenience for the test client making
HTTP requests on the server and then parsing back the response.
3. Easily invoke HTTP requests on the running server and have the response bound
back into a POJO (or an exception class if an error occurred)

