/*
 * Copyright 2014 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.common;

import static com.google.common.base.StandardSystemProperty.JAVA_SPECIFICATION_VERSION;
import static com.google.common.collect.Multimaps.transformValues;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static javax.tools.Diagnostic.Kind.ERROR;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.BasicAnnotationProcessor.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.truth.Correspondence;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationRule;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.processing.Filer;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BasicAnnotationProcessorTest {

  private abstract static class BaseAnnotationProcessor extends BasicAnnotationProcessor {

    static final String ENCLOSING_CLASS_NAME =
        BasicAnnotationProcessorTest.class.getCanonicalName();

    @Override
    public final SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface RequiresGeneratedCode {}

  @Retention(RetentionPolicy.SOURCE)
  @Target(ElementType.TYPE_PARAMETER)
  public @interface TypeParameterRequiresGeneratedCode {}

  /**
   * Rejects elements unless the class generated by {@link GeneratesCode}'s processor is present.
   */
  private static class RequiresGeneratedCodeProcessor extends BaseAnnotationProcessor {

    int rejectedRounds;
    final ImmutableList.Builder<ImmutableSetMultimap<String, Element>> processArguments =
        ImmutableList.builder();

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableList.of(
          new Step() {
            @Override
            public ImmutableSet<? extends Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              processArguments.add(ImmutableSetMultimap.copyOf(elementsByAnnotation));
              TypeElement requiredClass =
                  processingEnv.getElementUtils().getTypeElement("test.SomeGeneratedClass");
              if (requiredClass == null) {
                rejectedRounds++;
                return ImmutableSet.copyOf(elementsByAnnotation.values());
              }
              generateClass(processingEnv.getFiler(), "GeneratedByRequiresGeneratedCodeProcessor");
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(
                  ENCLOSING_CLASS_NAME + ".RequiresGeneratedCode",
                  ENCLOSING_CLASS_NAME + ".TypeParameterRequiresGeneratedCode");
            }
          },
          new Step() {
            @Override
            public ImmutableSet<? extends Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".AnAnnotation");
            }
          });
    }

    ImmutableList<ImmutableSetMultimap<String, Element>> processArguments() {
      return processArguments.build();
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  @Target(ElementType.METHOD)
  public @interface OneMethodAtATime {}

  private static class OneMethodAtATimeProcessor extends BaseAnnotationProcessor {

    int rejectedRounds;
    final ImmutableList.Builder<ImmutableSetMultimap<String, Element>> processArguments =
        ImmutableList.builder();

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableSet.of(
          new Step() {
            @Override
            public ImmutableSet<? extends Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              processArguments.add(ImmutableSetMultimap.copyOf(elementsByAnnotation));
              int numberOfAnnotatedElements = elementsByAnnotation.values().size();
              if (numberOfAnnotatedElements == 0) {
                return ImmutableSet.of();
              }

              generateClass(
                  processingEnv.getFiler(),
                  "GeneratedByOneMethodAtATimeProcessor_"
                      + elementsByAnnotation.values().iterator().next().getSimpleName());
              if (numberOfAnnotatedElements > 1) {
                rejectedRounds++;
              }
              return ImmutableSet.copyOf(
                  elementsByAnnotation.values().asList().subList(1, numberOfAnnotatedElements));
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".OneMethodAtATime");
            }
          });
    }

    ImmutableList<ImmutableSetMultimap<String, Element>> processArguments() {
      return processArguments.build();
    }
  }

  private static class OneOverloadedMethodAtATimeProcessor extends BaseAnnotationProcessor {

    int rejectedRounds;
    final ImmutableList.Builder<ImmutableSetMultimap<String, Element>> processArguments =
        ImmutableList.builder();

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableSet.of(
          new Step() {
            @Override
            public ImmutableSet<? extends Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              processArguments.add(ImmutableSetMultimap.copyOf(elementsByAnnotation));

              List<Element> annotatedElements = new ArrayList<>(elementsByAnnotation.values());
              int numberOfAnnotatedElements = annotatedElements.size();
              if (numberOfAnnotatedElements == 0) {
                return ImmutableSet.of();
              }
              if (numberOfAnnotatedElements > 1) {
                rejectedRounds++;
              }

              Name NameOfToBeProcessedElement;
              ImmutableSet<? extends Element> rejectedElements;
              if (numberOfAnnotatedElements > 2) {
                // Skip the first Element
                NameOfToBeProcessedElement = annotatedElements.get(1).getSimpleName();
                annotatedElements.remove(1);
                rejectedElements = ImmutableSet.copyOf(annotatedElements);
              } else {
                NameOfToBeProcessedElement = annotatedElements.get(0).getSimpleName();
                annotatedElements.remove(0);
                rejectedElements = ImmutableSet.copyOf(annotatedElements);
              }

              generateClass(
                  processingEnv.getFiler(),
                  String.format(
                      "GeneratedByOneMethodAtATimeProcessor_%d_%s",
                      numberOfAnnotatedElements > 1 ? rejectedRounds : rejectedRounds + 1,
                      Objects.requireNonNull(NameOfToBeProcessedElement)));
              return Objects.requireNonNull(rejectedElements);
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".OneMethodAtATime");
            }
          });
    }

    ImmutableList<ImmutableSetMultimap<String, Element>> processArguments() {
      return processArguments.build();
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface GeneratesCode {}

  /** Generates a class called {@code test.SomeGeneratedClass}. */
  public static class GeneratesCodeProcessor extends BaseAnnotationProcessor {
    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableList.of(
          new Step() {
            @Override
            public ImmutableSet<? extends Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              generateClass(processingEnv.getFiler(), "SomeGeneratedClass");
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".GeneratesCode");
            }
          });
    }
  }

  public @interface AnAnnotation {}

  /** When annotating a type {@code Foo}, generates a class called {@code FooXYZ}. */
  public static class AnAnnotationProcessor extends BaseAnnotationProcessor {

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableList.of(
          new Step() {
            @Override
            public ImmutableSet<Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              for (Element element : elementsByAnnotation.values()) {
                generateClass(processingEnv.getFiler(), element.getSimpleName() + "XYZ");
              }
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".AnAnnotation");
            }
          });
    }
  }

  /** An annotation which causes an annotation processing error. */
  public @interface CauseError {}

  /** Report an error for any class annotated. */
  public static class CauseErrorProcessor extends BaseAnnotationProcessor {

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableList.of(
          new Step() {
            @Override
            public ImmutableSet<Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              for (Element e : elementsByAnnotation.values()) {
                processingEnv.getMessager().printMessage(ERROR, "purposeful error", e);
              }
              return ImmutableSet.copyOf(elementsByAnnotation.values());
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(ENCLOSING_CLASS_NAME + ".CauseError");
            }
          });
    }
  }

  public static class MissingAnnotationProcessor extends BaseAnnotationProcessor {

    private ImmutableSetMultimap<String, Element> elementsByAnnotation;

    @Override
    protected Iterable<? extends Step> steps() {
      return ImmutableList.of(
          new Step() {
            @Override
            public ImmutableSet<Element> process(
                ImmutableSetMultimap<String, Element> elementsByAnnotation) {
              MissingAnnotationProcessor.this.elementsByAnnotation = elementsByAnnotation;
              for (Element element : elementsByAnnotation.values()) {
                generateClass(processingEnv.getFiler(), element.getSimpleName() + "XYZ");
              }
              return ImmutableSet.of();
            }

            @Override
            public ImmutableSet<String> annotations() {
              return ImmutableSet.of(
                  "test.SomeNonExistentClass", ENCLOSING_CLASS_NAME + ".AnAnnotation");
            }
          });
    }

    ImmutableSetMultimap<String, Element> getElementsByAnnotation() {
      return elementsByAnnotation;
    }
  }

  @SuppressWarnings("deprecation") // Deprecated ProcessingStep is being explicitly tested.
  static final class MultiAnnotationProcessingStep implements ProcessingStep {

    private SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation;

    @Override
    public ImmutableSet<? extends Class<? extends Annotation>> annotations() {
      return ImmutableSet.of(AnAnnotation.class, ReferencesAClass.class);
    }

    @Override
    public ImmutableSet<? extends Element> process(
        SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
      this.elementsByAnnotation = elementsByAnnotation;
      return ImmutableSet.of();
    }

    SetMultimap<Class<? extends Annotation>, Element> getElementsByAnnotation() {
      return elementsByAnnotation;
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface ReferencesAClass {
    Class<?> value();
  }

  @Rule public CompilationRule compilation = new CompilationRule();

  private void requiresGeneratedCodeDeferralTest(
      JavaFileObject dependentTestFileObject, JavaFileObject generatesCodeFileObject) {
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    Compilation compilation =
        javac()
            .withProcessors(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
            .compile(dependentTestFileObject, generatesCodeFileObject);
    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.GeneratedByRequiresGeneratedCodeProcessor");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(0);
  }

  private void requiresGeneratedCodeDeferralTest(JavaFileObject dependentTestFileObject) {
    JavaFileObject generatesCodeFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassB",
            "package test;",
            "",
            "@" + GeneratesCode.class.getCanonicalName(),
            "public class ClassB {}");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject, generatesCodeFileObject);
  }

  @Test
  public void properlyDefersProcessing_typeElement() {
    JavaFileObject dependentTestFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + RequiresGeneratedCode.class.getCanonicalName(),
            "public class ClassA {",
            "  SomeGeneratedClass sgc;",
            "}");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject);
  }

  @Test
  public void properlyDefersProcessing_packageElement() {
    JavaFileObject dependentTestFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + GeneratesCode.class.getCanonicalName(),
            "public class ClassA {",
            "}");
    JavaFileObject generatesCodeFileObject =
        JavaFileObjects.forSourceLines(
            "test.package-info",
            "@" + RequiresGeneratedCode.class.getCanonicalName(),
            "@" + ReferencesAClass.class.getCanonicalName() + "(SomeGeneratedClass.class)",
            "package test;");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject, generatesCodeFileObject);
  }

  @Test
  public void properlyDefersProcessing_argumentElement() {
    JavaFileObject dependentTestFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "public class ClassA {",
            "  SomeGeneratedClass sgc;",
            "  public void myMethod(@"
                + RequiresGeneratedCode.class.getCanonicalName()
                + " int myInt)",
            "  {}",
            "}");
    JavaFileObject generatesCodeFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassB",
            "package test;",
            "",
            "public class ClassB {",
            "  public void myMethod(@" + GeneratesCode.class.getCanonicalName() + " int myInt) {}",
            "}");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject, generatesCodeFileObject);
  }

  @Test
  public void properlyDefersProcessing_recordComponent() {
    double version = Double.parseDouble(Objects.requireNonNull(JAVA_SPECIFICATION_VERSION.value()));
    assume().that(version).isAtLeast(16.0);
    JavaFileObject dependentTestFileObject =
        JavaFileObjects.forSourceLines(
            "test.RecordA",
            "package test;",
            "",
            "public record RecordA( @"
                + RequiresGeneratedCode.class.getCanonicalName()
                + " SomeGeneratedClass sgc) {",
            "}");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject);
  }

  @Test
  public void properlyDefersProcessing_typeParameter() {
    JavaFileObject dependentTestFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "public class ClassA <@"
                + TypeParameterRequiresGeneratedCode.class.getCanonicalName()
                + " T extends SomeGeneratedClass> {",
            "}");
    requiresGeneratedCodeDeferralTest(dependentTestFileObject);
  }

  @Test
  public void properlyDefersProcessing_nestedTypeValidBeforeOuterType() {
    JavaFileObject source =
        JavaFileObjects.forSourceLines(
            "test.ValidInRound2",
            "package test;",
            "",
            "@" + AnAnnotation.class.getCanonicalName(),
            "public class ValidInRound2 {",
            "  ValidInRound1XYZ vir1xyz;",
            "  @" + AnAnnotation.class.getCanonicalName(),
            "  static class ValidInRound1 {}",
            "}");
    Compilation compilation =
        javac().withProcessors(new AnAnnotationProcessor()).compile(source);
    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.ValidInRound2XYZ");
  }

  @Test
  public void properlyDefersProcessing_rejectsElement() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + RequiresGeneratedCode.class.getCanonicalName(),
            "public class ClassA {",
            "  @" + AnAnnotation.class.getCanonicalName(),
            "  public void method() {}",
            "}");
    JavaFileObject classBFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassB",
            "package test;",
            "",
            "@" + GeneratesCode.class.getCanonicalName(),
            "public class ClassB {}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    Compilation compilation =
        javac()
            .withProcessors(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
            .compile(classAFileObject, classBFileObject);
    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.GeneratedByRequiresGeneratedCodeProcessor");
    assertThat(requiresGeneratedCodeProcessor.rejectedRounds).isEqualTo(1);

    // Re b/118372780: Assert that the right deferred elements are passed back, and not any enclosed
    // elements annotated with annotations from a different step.
    assertThat(requiresGeneratedCodeProcessor.processArguments())
        .comparingElementsUsing(setMultimapValuesByString())
        .containsExactly(
            ImmutableSetMultimap.of(RequiresGeneratedCode.class.getCanonicalName(), "test.ClassA"),
            ImmutableSetMultimap.of(RequiresGeneratedCode.class.getCanonicalName(), "test.ClassA"))
        .inOrder();
  }

  private static <K, V>
      Correspondence<SetMultimap<K, V>, SetMultimap<K, String>> setMultimapValuesByString() {
    return Correspondence.from(
        (actual, expected) ->
            ImmutableSetMultimap.copyOf(transformValues(actual, Object::toString)).equals(expected),
        "is equivalent comparing multimap values by `toString()` to");
  }

  @Test
  public void properlyDefersProcessing_rejectsExecutableElement() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "public class ClassA {",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void method0() {}",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void method1() {}",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void method2() {}",
            "}");
    OneMethodAtATimeProcessor oneMethodAtATimeProcessor = new OneMethodAtATimeProcessor();
    Compilation compilation =
        javac().withProcessors(oneMethodAtATimeProcessor).compile(classAFileObject);

    assertThat(compilation).succeeded();
    assertThat(oneMethodAtATimeProcessor.rejectedRounds).isEqualTo(2);

    assertThat(oneMethodAtATimeProcessor.processArguments())
        .comparingElementsUsing(setMultimapValuesByString())
        .containsExactly(
            ImmutableSetMultimap.of(
                OneMethodAtATime.class.getCanonicalName(), "method0()",
                OneMethodAtATime.class.getCanonicalName(), "method1()",
                OneMethodAtATime.class.getCanonicalName(), "method2()"),
            ImmutableSetMultimap.of(
                OneMethodAtATime.class.getCanonicalName(), "method1()",
                OneMethodAtATime.class.getCanonicalName(), "method2()"),
            ImmutableSetMultimap.of(OneMethodAtATime.class.getCanonicalName(), "method2()"))
        .inOrder();
  }

  @Test
  public void properlyDefersProcessing_handlesOverloadedExecutableElements() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "public class ClassA {",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void overloadedMethod(int x) {}",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void overloadedMethod(float x) {}",
            "  @" + OneMethodAtATime.class.getCanonicalName(),
            "  public void overloadedMethod(double x) {}",
            "}");
    OneOverloadedMethodAtATimeProcessor oneOverloadedMethodAtATimeProcessor =
        new OneOverloadedMethodAtATimeProcessor();
    Compilation compilation =
        javac().withProcessors(oneOverloadedMethodAtATimeProcessor).compile(classAFileObject);

    assertThat(compilation).succeeded();
    assertThat(oneOverloadedMethodAtATimeProcessor.rejectedRounds).isEqualTo(2);

    assertThat(oneOverloadedMethodAtATimeProcessor.processArguments())
        .comparingElementsUsing(setMultimapValuesByString())
        .containsExactly(
            ImmutableSetMultimap.of(
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(int)",
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(float)",
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(double)"),
            ImmutableSetMultimap.of(
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(int)",
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(double)"),
            ImmutableSetMultimap.of(
                OneMethodAtATime.class.getCanonicalName(), "overloadedMethod(double)"))
        .inOrder();
  }

  @Test
  public void properlySkipsMissingAnnotations_generatesClass() {
    JavaFileObject source =
        JavaFileObjects.forSourceLines(
            "test.ValidInRound2",
            "package test;",
            "",
            "@" + AnAnnotation.class.getCanonicalName(),
            "public class ValidInRound2 {",
            "  ValidInRound1XYZ vir1xyz;",
            "  @" + AnAnnotation.class.getCanonicalName(),
            "  static class ValidInRound1 {}",
            "}");
    Compilation compilation =
        javac().withProcessors(new MissingAnnotationProcessor()).compile(source);
    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.ValidInRound2XYZ");
  }

  @Test
  public void properlySkipsMissingAnnotations_passesValidAnnotationsToProcess() {
    JavaFileObject source =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + AnAnnotation.class.getCanonicalName(),
            "public class ClassA {",
            "}");
    MissingAnnotationProcessor missingAnnotationProcessor = new MissingAnnotationProcessor();
    assertThat(javac().withProcessors(missingAnnotationProcessor).compile(source)).succeeded();
    assertThat(missingAnnotationProcessor.getElementsByAnnotation().keySet())
        .containsExactly(AnAnnotation.class.getCanonicalName());
    assertThat(missingAnnotationProcessor.getElementsByAnnotation().values()).hasSize(1);
  }

  @Test
  public void reportsMissingType() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + RequiresGeneratedCode.class.getCanonicalName(),
            "public class ClassA {",
            "  SomeGeneratedClass bar;",
            "}");
    Compilation compilation =
        javac().withProcessors(new RequiresGeneratedCodeProcessor()).compile(classAFileObject);
    assertThat(compilation)
        .hadErrorContaining(RequiresGeneratedCodeProcessor.class.getCanonicalName())
        .inFile(classAFileObject)
        .onLineContaining("class ClassA");
  }

  @Test
  public void reportsMissingTypeSuppressedWhenOtherErrors() {
    JavaFileObject classAFileObject =
        JavaFileObjects.forSourceLines(
            "test.ClassA",
            "package test;",
            "",
            "@" + CauseError.class.getCanonicalName(),
            "public class ClassA {}");
    Compilation compilation =
        javac().withProcessors(new CauseErrorProcessor()).compile(classAFileObject);
    assertThat(compilation).hadErrorContaining("purposeful");
  }

  @Test
  public void processingStepAsStepAnnotationsNamesMatchClasses() {
    Step step = BasicAnnotationProcessor.asStep(new MultiAnnotationProcessingStep());

    assertThat(step.annotations())
        .containsExactly(
            AnAnnotation.class.getCanonicalName(), ReferencesAClass.class.getCanonicalName());
  }

  /**
   * Tests that a {@link ProcessingStep} passed to {@link
   * BasicAnnotationProcessor#asStep(ProcessingStep)} still gets passed the correct arguments to
   * {@link Step#process(ImmutableSetMultimap)}.
   */
  @Test
  public void processingStepAsStepProcessElementsMatchClasses() {
    Elements elements = compilation.getElements();
    String anAnnotationName = AnAnnotation.class.getCanonicalName();
    String referencesAClassName = ReferencesAClass.class.getCanonicalName();
    TypeElement anAnnotationElement = elements.getTypeElement(anAnnotationName);
    TypeElement referencesAClassElement = elements.getTypeElement(referencesAClassName);
    MultiAnnotationProcessingStep processingStep = new MultiAnnotationProcessingStep();

    BasicAnnotationProcessor.asStep(processingStep)
        .process(
            ImmutableSetMultimap.of(
                anAnnotationName,
                anAnnotationElement,
                referencesAClassName,
                referencesAClassElement));

    assertThat(processingStep.getElementsByAnnotation())
        .containsExactly(
            AnAnnotation.class,
            anAnnotationElement,
            ReferencesAClass.class,
            referencesAClassElement);
  }

  private static void generateClass(Filer filer, String generatedClassName) {
    PrintWriter writer = null;
    try {
      writer = new PrintWriter(filer.createSourceFile("test." + generatedClassName).openWriter());
      writer.println("package test;");
      writer.println("public class " + generatedClassName + " {}");
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
  }
}
