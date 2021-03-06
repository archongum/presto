/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.testng.services;

import com.google.common.annotations.VisibleForTesting;
import org.testng.IClassListener;
import org.testng.ITestClass;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.testng.services.Listeners.reportListenerFailure;
import static java.util.stream.Collectors.joining;

public class ReportUnannotatedMethods
        implements IClassListener
{
    @Override
    public void onBeforeClass(ITestClass testClass)
    {
        try {
            reportUnannotatedTestMethods(testClass);
        }
        catch (RuntimeException | Error e) {
            reportListenerFailure(
                    ReportUnannotatedMethods.class,
                    "Failed to process %s: \n%s",
                    testClass,
                    getStackTraceAsString(e));
        }
    }

    private void reportUnannotatedTestMethods(ITestClass testClass)
    {
        Class<?> realClass = testClass.getRealClass();

        if (realClass.getSuperclass() != null &&
                "io.prestosql.tempto.internal.convention.ConventionBasedTestProxyGenerator$ConventionBasedTestProxy".equals(realClass.getSuperclass().getName())) {
            // Ignore tempto generated convention tests.
            return;
        }

        List<Method> unannotatedTestMethods = findUnannotatedTestMethods(realClass);
        if (!unannotatedTestMethods.isEmpty()) {
            reportListenerFailure(
                    ReportUnannotatedMethods.class,
                    "Test class %s has methods which are public but not explicitly annotated. Are they missing @Test?%s",
                    realClass.getName(),
                    unannotatedTestMethods.stream()
                            .map(Method::toString)
                            .collect(joining("\n\t\t", "\n\t\t", "")));
        }
    }

    @VisibleForTesting
    static List<Method> findUnannotatedTestMethods(Class<?> realClass)
    {
        return Arrays.stream(realClass.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> !method.isBridge())
                .filter(method -> !isTestMethod(method))
                .filter(method -> !isTemptoSpiMethod(method))
                .collect(toImmutableList());
    }

    @Override
    public void onAfterClass(ITestClass testClass) {}

    /**
     * Is explicitly annotated as @Test, @BeforeMethod, @DataProvider, etc.
     */
    private static boolean isTestMethod(Method method)
    {
        if (isTestAnnotated(method)) {
            return true;
        }

        if (method.getDeclaringClass() == Object.class) {
            return true;
        }
        Class<?> superclass = method.getDeclaringClass().getSuperclass();
        Method overridden;
        try {
            // Simplistic override detection
            overridden = superclass.getMethod(method.getName(), method.getParameterTypes());
        }
        catch (NoSuchMethodException ignored) {
            return false;
        }

        return isTestMethod(overridden);
    }

    private static boolean isTestAnnotated(Method method)
    {
        return Arrays.stream(method.getAnnotations())
                .map(Annotation::annotationType)
                .anyMatch(annotationClass -> {
                    if ("org.openjdk.jmh.annotations.Benchmark".equals(annotationClass.getName())) {
                        return true;
                    }
                    if (org.testng.annotations.Test.class.getPackage().equals(annotationClass.getPackage())) {
                        // testng annotation (@Test, @Before*, @DataProvider, etc.)
                        return true;
                    }
                    if (isTemptoClass(annotationClass)) {
                        // tempto annotation (@BeforeTestWithContext, @AfterTestWithContext)
                        return true;
                    }
                    return false;
                });
    }

    private static boolean isTemptoSpiMethod(Method method)
    {
        return Stream.of(method.getDeclaringClass().getInterfaces())
                .filter(ReportUnannotatedMethods::isTemptoClass)
                .map(Class::getMethods)
                .flatMap(Stream::of)
                .anyMatch(actualMethod -> overrides(method, actualMethod));
    }

    private static boolean overrides(Method first, Method second)
    {
        if (!first.getName().equals(second.getName())) {
            return false;
        }

        if (first.getParameterTypes().length != second.getParameterTypes().length) {
            return false;
        }

        for (int i = 0; i < first.getParameterTypes().length; i++) {
            if (!first.getParameterTypes()[i].getName().equals(second.getParameterTypes()[i].getName())) {
                return false;
            }
        }

        return true;
    }

    public static boolean isTemptoClass(Class<?> aClass)
    {
        return "io.prestosql.tempto".equals(aClass.getPackage().getName());
    }
}
