package tests;

import examples.FindKotlinGeneratedMethods;
import junit.framework.TestCase;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class FindKotlinGeneratedMethodsTest {
    @Test
    public void testDataClass() throws Exception {
        byte[] bytes = loadClassBytes(SampleDataClass.class);
        TestCase.assertEquals(
                Arrays.asList(
                        "component1()Ljava/lang/String;",
                        "component2()I",
                        "component3()Ljava/lang/String;",
                        "copy(Ljava/lang/String;ILjava/lang/String;)Ltests/SampleDataClass;",
                        "equals(Ljava/lang/Object;)Z",
                        "hashCode()I",
                        "toString()Ljava/lang/String;",
                        "getAge()I",
                        "setAge(I)V",
                        "getLocation()Ljava/lang/String;",
                        "setLocation(Ljava/lang/String;)V",
                        "getName()Ljava/lang/String;"
                ),
                FindKotlinGeneratedMethods.run(bytes)
        );
    }

    private byte[] loadClassBytes(Class<SampleDataClass> klass) throws Exception {
        URI testOutputDirectory = klass.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path dataClassLocation =
                Paths.get(testOutputDirectory).resolve(SampleDataClass.class.getName().replace('.', '/') + ".class");
        return Files.readAllBytes(dataClassLocation);
    }
}
