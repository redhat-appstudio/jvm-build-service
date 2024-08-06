package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.signatureToJavaCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class JavaCodeSignatureWriterTest {
    @Test
    void testExtendsObjectRemoval() {
        var name = "fail";
        var desc = "(Ljava/util/function/Supplier;)Ljava/lang/Object;";
        var signature = "<V:Ljava/lang/Object;>(Ljava/util/function/Supplier<Ljava/lang/String;>;)TV;";
        var exceptions = List.<String>of();
        var expected = "<V> V fail(java.util.function.Supplier<java.lang.String>)";
        assertThat(signatureToJavaCode(name, desc, signature, exceptions)).isEqualTo(expected);
    }

    @Test
    void testArray() {
        var name = "func";
        var exceptions = List.of("java.io.IOException");
        var signature = "(ILjava/lang/String;[I)J";
        var expectedExceptions = " throws " + String.join(", ", exceptions);
        var expected = "long " + name + "(int, java.lang.String, int[])" + expectedExceptions;
        assertThat(signatureToJavaCode(name, null, signature, exceptions)).isEqualTo(expected);
    }

    @Test
    void testDiamondWithArray() {
        var name = "lexicographicalComparator";
        var desc = "()Ljava/util/Comparator;";
        var signature = "()Ljava/util/Comparator<[I>;";
        var exceptions = List.<String>of();
        var expected = "java.util.Comparator<int[]> lexicographicalComparator()";
        assertThat(signatureToJavaCode(name, desc, signature, exceptions)).isEqualTo(expected);
    }

    @Test
    void testDiamondWithUnknownType() {
        var name = "createParentDirectories";
        var desc = "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)V";
        var signature = "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute<*>;)V";
        var exceptions = List.of("java.io.IOException");
        var expected = "void createParentDirectories(java.nio.file.Path, java.nio.file.attribute.FileAttribute<?>) throws java.io.IOException";
        assertThat(signatureToJavaCode(name, desc, signature, exceptions)).isEqualTo(expected);
    }

    @Test
    void testDiamondWithTwoUnknownTypes() {
        var name = "hasCycle";
        var desc = "(Lcom/google/common/graph/Network;)Z";
        var signature = "(Lcom/google/common/graph/Network<**>;)Z";
        var expected = "boolean hasCycle(com.google.common.graph.Network<?, ?>)";
        assertThat(signatureToJavaCode(name, desc, signature, null)).isEqualTo(expected);
    }

    @Test
    void testDiamondWithUnknownTypeInMiddle() {
        var name = "toImmutableMultiset";
        var desc = "()Ljava/util/stream/Collector;";
        var signature = "<E:Ljava/lang/Object;>()Ljava/util/stream/Collector<TE;*Lcom/google/common/collect/ImmutableMultiset<TE;>;>;";
        var expected = "<E> java.util.stream.Collector<E, ?, com.google.common.collect.ImmutableMultiset<E>> toImmutableMultiset()";
        assertThat(signatureToJavaCode(name, desc, signature, null)).isEqualTo(expected);
    }
}
