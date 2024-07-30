package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.fixName;
import static java.lang.System.Logger.Level.DEBUG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

public class JavaCodeSignatureWriter extends SignatureWriter {
    private static final System.Logger LOGGER = System.getLogger(JavaCodeSignatureWriter.class.getName());

    private final StringBuilder stringBuilder;

    private final StringBuilder formalsStringBuilder = new StringBuilder();

    private final StringBuilder extendsStringBuilder = new StringBuilder();

    private final List<JavaCodeSignatureWriter> parameters = new ArrayList<>();

    private boolean hasFormals;

    private boolean hasArray;

    private boolean hasTypeArgument;

    private int argumentStack = 1;

    private JavaCodeSignatureWriter returnType;

    private JavaCodeSignatureWriter superClass;

    public JavaCodeSignatureWriter() {
        this(new StringBuilder());
    }

    private JavaCodeSignatureWriter(StringBuilder stringBuilder) {
        this.stringBuilder = stringBuilder;
    }

    @Override
    public void visitFormalTypeParameter(String name) {
        LOGGER.log(DEBUG, "visitFormalTypeParameter: name={0}", name);

        if (!hasFormals) {
            hasFormals = true;
            formalsStringBuilder.append('<');
        } else {
            formalsStringBuilder.append(", ");
        }

        formalsStringBuilder.append(fixName(name));
        extendsStringBuilder.append(" extends ");
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
        return this;
    }

    @Override
    public SignatureVisitor visitSuperclass() {
        endFormals();
        return (superClass = new JavaCodeSignatureWriter());
    }

    @Override
    public SignatureVisitor visitParameterType() {
        LOGGER.log(DEBUG, "visitParameterType");
        endFormals();
        JavaCodeSignatureWriter writer = new JavaCodeSignatureWriter();
        parameters.add(writer);
        return writer;
    }

    @Override
    public SignatureVisitor visitReturnType() {
        endFormals();
        return (returnType = new JavaCodeSignatureWriter());
    }

    @Override
    public SignatureVisitor visitExceptionType() {
        return this;
    }

    @Override
    public void visitBaseType(char descriptor) {
        LOGGER.log(DEBUG, "visitBaseType: descriptor={0}", descriptor);
        var typeDescriptor = String.valueOf(descriptor);
        var type = Type.getType(typeDescriptor);
        var className = type.getClassName();
        (hasFormals ? formalsStringBuilder : stringBuilder).append(fixName(className));

        if (hasArray) {
            hasArray = false;
            (hasFormals ? formalsStringBuilder : stringBuilder).append("[]");
        }
    }

    @Override
    public void visitTypeVariable(String name) {
        LOGGER.log(DEBUG, "visitTypeVariable: name={0}", name);
        hasTypeArgument = true;
        (hasFormals ? formalsStringBuilder : stringBuilder).append(fixName(name));
    }

    @Override
    public SignatureVisitor visitArrayType() {
        LOGGER.log(DEBUG, "visitArrayType");
        hasArray = true;
        hasTypeArgument = true;
        return this;
    }

    @Override
    public void visitClassType(String name) {
        LOGGER.log(DEBUG, "visitClassType: name={0}", name);
        hasTypeArgument = true;

        if (!"java/lang/Object".equals(name) || extendsStringBuilder.isEmpty()) {
            if (!extendsStringBuilder.isEmpty()) {
                (hasFormals ? formalsStringBuilder : stringBuilder).append(extendsStringBuilder);
            }

            (hasFormals ? formalsStringBuilder : stringBuilder).append(fixName(name));
        }

        argumentStack <<= 1;
        extendsStringBuilder.setLength(0);
    }

    @Override
    public void visitInnerClassType(String name) {
        LOGGER.log(DEBUG, "visitInnerClassType: name={0}", name);
        hasTypeArgument = true;
        endArguments();
        (hasFormals ? formalsStringBuilder : stringBuilder).append('.').append(fixName(name));
        argumentStack <<= 1;
    }

    @Override
    public void visitTypeArgument() {
        LOGGER.log(DEBUG, "visitTypeArgument: argumentStack={0}", argumentStack);

        if (!hasTypeArgument) {
            (hasFormals ? formalsStringBuilder : stringBuilder).append('?');
        }

        if ((argumentStack & 1) == 0) {
            argumentStack |= 1;
            (hasFormals ? formalsStringBuilder : stringBuilder).append('<');
        } else {
            (hasFormals ? formalsStringBuilder : stringBuilder).append(", ");
        }

        hasTypeArgument = false;
    }

    @Override
    public SignatureVisitor visitTypeArgument(char wildcard) {
        LOGGER.log(DEBUG, "visitTypeArgument: wildcard=''{0}''", wildcard);
        visitTypeArgument();

        switch (wildcard) {
            case '+' -> (hasFormals ? formalsStringBuilder : stringBuilder).append("? extends ");
            case '-' -> (hasFormals ? formalsStringBuilder : stringBuilder).append("? super ");
        }

       return (argumentStack & (1 << 31)) == 0 ? this : new JavaCodeSignatureWriter(stringBuilder);
    }

    @Override
    public void visitEnd() {
        LOGGER.log(DEBUG, "visitEnd");
        endArguments();
    }

    @Override
    public String toString() {
        return stringBuilder.toString();
    }

    private void endFormals() {
        LOGGER.log(DEBUG, "endFormals");

        if (hasFormals) {
            stringBuilder.append('>');
            hasFormals = false;
        }
    }

    private void endArguments() {
        LOGGER.log(DEBUG, "endArguments");

        if ((argumentStack & 1) == 1) {
            if (!hasTypeArgument) {
                (hasFormals ? formalsStringBuilder : stringBuilder).append('?');
            }

            (hasFormals ? formalsStringBuilder : stringBuilder).append('>');
        }

        argumentStack >>>= 1;
    }

    public JavaCodeSignatureWriter getReturnType() {
        return returnType;
    }

    public List<JavaCodeSignatureWriter> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    public String getFormals() {
        return formalsStringBuilder.toString();
    }

    public JavaCodeSignatureWriter getSuperClass() {
        return superClass;
    }
}
