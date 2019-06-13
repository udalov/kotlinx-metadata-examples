package examples;

import kotlinx.metadata.Flag;
import kotlinx.metadata.KmDeclarationContainer;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class uses kotlinx-metadata-jvm and ASM to load JVM signatures of methods in the Kotlin .class file, that are
 * automatically generated by the Kotlin compiler. Such methods do not have any source representation and thus do not
 * have a line number table, which makes code coverage tools treat them as non-covered in any execution of the program.
 *
 * For more information, see https://youtrack.jetbrains.com/issue/KT-18383
 */
public class FindKotlinGeneratedMethods {
    /**
     * @param bytes array containing the bytes of the .class file
     * @return list of JVM signatures of methods, automatically generated by the Kotlin compiler,
     *         for example: `equals(Ljava/lang/Object;)Z`
     */
    @NotNull
    public static List<String> run(@NotNull byte[] bytes) {
        // First, load the @kotlin.Metadata annotation
        AnnotationNode annotationNode = loadKotlinMetadataAnnotationNode(bytes);
        if (annotationNode == null) {
            throw new IllegalArgumentException("Not a Kotlin .class file! No @kotlin.Metadata annotation found");
        }

        // Then convert it to KotlinClassHeader
        KotlinClassHeader header = createHeader(annotationNode);

        // Read and parse the metadata
        KotlinClassMetadata metadata = KotlinClassMetadata.read(header);

        // The given .class file could have been either a normal Kotlin class, or a file facade (a .class file
        // generated for top-level functions/properties in one source file).
        // Extract the declaration container, which will give us direct access to functions/properties in that class file
        KmDeclarationContainer container;
        if (metadata instanceof KotlinClassMetadata.Class) {
            container = ((KotlinClassMetadata.Class) metadata).toKmClass();
        } else if (metadata instanceof KotlinClassMetadata.FileFacade) {
            container = ((KotlinClassMetadata.FileFacade) metadata).toKmPackage();
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart) {
            container = ((KotlinClassMetadata.MultiFileClassPart) metadata).toKmPackage();
        } else {
            return Collections.emptyList();
        }

        // Get all generated JVM methods from the declaration container
        List<JvmMethodSignature> result = getGeneratedMethods(container);

        // Transform each JVM method signature to a simple string, concatenating the name and the method descriptor
        return result.stream().map(JvmMethodSignature::asString).collect(Collectors.toList());
    }

    /**
     * Loads the AnnotationNode corresponding to the @kotlin.Metadata annotation on the .class file,
     * or returns null if there's no such annotation.
     */
    @Nullable
    private static AnnotationNode loadKotlinMetadataAnnotationNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, Opcodes.V1_8);
        return node.visibleAnnotations.stream()
                .filter(it -> "Lkotlin/Metadata;".equals(it.desc))
                .findFirst()
                .orElse(null);
    }

    /**
     * Converts the given AnnotationNode representing the @kotlin.Metadata annotation into KotlinClassHeader,
     * to be able to use it in KotlinClassMetadata.read.
     */
    @NotNull
    private static KotlinClassHeader createHeader(AnnotationNode node) {
        Integer kind = null;
        int[] metadataVersion = null;
        int[] bytecodeVersion = null;
        String[] data1 = null;
        String[] data2 = null;
        String extraString = null;
        String packageName = null;
        Integer extraInt = null;

        Iterator<Object> it = node.values.iterator();
        while (it.hasNext()) {
            String name = (String) it.next();
            Object value = it.next();

            switch (name) {
                case "k": kind = (Integer) value; break;
                case "mv": metadataVersion = listToIntArray(value); break;
                case "bv": bytecodeVersion = listToIntArray(value); break;
                case "d1": data1 = listToStringArray(value); break;
                case "d2": data2 = listToStringArray(value); break;
                case "xs": extraString = (String) value; break;
                case "pn": packageName = (String) value; break;
                case "xi": extraInt = (Integer) value; break;
            }
        }

        return new KotlinClassHeader(
                kind, metadataVersion, bytecodeVersion, data1, data2, extraString, packageName, extraInt
        );
    }

    @SuppressWarnings("unchecked")
    private static int[] listToIntArray(Object list) {
        return ((List<Integer>) list).stream().mapToInt(it -> it).toArray();
    }

    @SuppressWarnings("unchecked")
    private static String[] listToStringArray(Object list) {
        return ((List<String>) list).toArray(new String[0]);
    }

    /**
     * Returns JVM signatures of generated methods in the given declaration container.
     *
     * A JVM method is considered "generated" here if it's a Kotlin function marked with the "synthesized" flag in the metadata,
     * or an accessor of a Kotlin property _not_ marked with the "is not default" flag in the metadata.
     *
     * Synthesized functions include:
     * <ul>
     *   <li>methods for data classes: componentN, copy, equals, hashCode, toString</li>
     *   <li>values/valueOf methods for enum classes</li>
     *   <li>box/unbox methods for inline classes</li>
     * </ul>
     *
     * The "is not default" flag is set in the metadata if the corresponding property accessor has non-trivial body in the source code.
     * The property accessor is considered generated if it isn't marked with that flag.
     */
    @NotNull
    private static List<JvmMethodSignature> getGeneratedMethods(KmDeclarationContainer container) {
        List<JvmMethodSignature> result = new ArrayList<>();
        for (KmFunction function : container.getFunctions()) {
            if (Flag.Function.IS_SYNTHESIZED.invoke(function.getFlags())) {
                JvmMethodSignature signature = JvmExtensionsKt.getSignature(function);
                if (signature != null) {
                    result.add(signature);
                }
            }
        }
        for (KmProperty property : container.getProperties()) {
            if (!Flag.PropertyAccessor.IS_NOT_DEFAULT.invoke(property.getGetterFlags())) {
                JvmMethodSignature signature = JvmExtensionsKt.getGetterSignature(property);
                if (signature != null) {
                    result.add(signature);
                }
            }
            if (!Flag.PropertyAccessor.IS_NOT_DEFAULT.invoke(property.getSetterFlags())) {
                JvmMethodSignature signature = JvmExtensionsKt.getSetterSignature(property);
                if (signature != null) {
                    result.add(signature);
                }
            }
        }

        return result;
    }
}
