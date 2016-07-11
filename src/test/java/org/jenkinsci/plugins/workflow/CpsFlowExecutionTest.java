/*
 * The MIT License
 *
 * Copyright 2015 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.AbortException;
import hudson.model.Result;
import hudson.model.TaskListener;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.groovy.transform.ASTTransformationVisitor;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsStepContext;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.pickles.SingleTypedPickleFactory;
import org.jenkinsci.plugins.workflow.support.pickles.TryRepeatedly;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.MemoryAssert;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestExtension;

public class CpsFlowExecutionTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();
    
    private static WeakReference<ClassLoader> LOADER;
    public static void register(Object o) {
        LOADER = new WeakReference<>(o.getClass().getClassLoader());
    }
    @Ignore("TODO fails in Jenkins 2 for reasons TBD (no root references detected)")
    @Test public void loaderReleased() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(CpsFlowExecutionTest.class.getName() + ".register(this)"));
                story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                assertNotNull(LOADER);
                System.err.println(LOADER.get());
                try {
                    // TODO in Groovy 1.8.9 this keeps static state, but only for the last script (as also noted in JENKINS-23762).
                    // The fix of GROOVY-5025 (62bfb68) in 1.9 addresses this, which we would get if JENKINS-21249 is implemented.
                    Field f = ASTTransformationVisitor.class.getDeclaredField("compUnit");
                    f.setAccessible(true);
                    f.set(null, null);
                } catch (NoSuchFieldException e) {
                    // assuming that Groovy version is newer
                }
                MemoryAssert.assertGC(LOADER);
            }
        });
    }

    /* Failed attempt to make the test print soft references it has trouble clearing. The test ultimately passes, but cannot find the soft references via any root path.
    private static void assertGC(WeakReference<?> reference) throws Exception {
        assertTrue(true); reference.get(); // preload any needed classes!
        Set<Object[]> objects = new HashSet<Object[]>();
        int size = 1024;
        while (reference.get() != null) {
            LiveEngine e = new LiveEngine();
            // The default filter, ScannerUtils.skipNonStrongReferencesFilter(), omits SoftReference.referent that we care about.
            // The constructor accepting a filter ANDs it with the default filter, making it useless for this purpose.
            Field f = LiveEngine.class.getDeclaredField("filter");
            f.setAccessible(true);
            f.set(e, new Filter() {
                final Field referent = Reference.class.getDeclaredField("referent");
                @Override public boolean accept(Object obj, Object referredFrom, Field reference) {
                    return !(referent.equals(reference) && referredFrom instanceof WeakReference);
                }
            });
            System.err.println(e.trace(Collections.singleton(reference.get()), null));
            System.err.println("allocating " + size);
            try {
                objects.add(new Object[size]);
            } catch (OutOfMemoryError ignore) {
                break;
            }
            size *= 1.1;
            System.gc();
        }
        objects = null;
        System.gc();
        Object obj = reference.get();
        if (obj != null) {
            fail(LiveReferences.fromRoots(Collections.singleton(obj)).toString());
        }
    }
    */

    @Test public void getCurrentExecutions() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "echo 'a step'; semaphore 'one'; retry(2) {semaphore 'two'; node {semaphore 'three'}; semaphore 'four'}; semaphore 'five'; " +
                        "parallel a: {node {semaphore 'six'}}, b: {semaphore 'seven'}; semaphore 'eight'", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("one/1", b);
                FlowExecution e = b.getExecution();
                assertStepExecutions(e, "semaphore");
                SemaphoreStep.success("one/1", null);
                SemaphoreStep.waitForStart("two/1", b);
                assertStepExecutions(e, "retry {}", "semaphore");
                SemaphoreStep.success("two/1", null);
                SemaphoreStep.waitForStart("three/1", b);
                assertStepExecutions(e, "retry {}", "node {}", "semaphore");
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                CpsFlowExecution e = (CpsFlowExecution) b.getExecution();
                assertTrue(e.isSandbox());
                SemaphoreStep.success("three/1", null);
                SemaphoreStep.waitForStart("four/1", b);
                assertStepExecutions(e, "retry {}", "semaphore");
                SemaphoreStep.failure("four/1", new AbortException("try again"));
                SemaphoreStep.waitForStart("two/2", b);
                assertStepExecutions(e, "retry {}", "semaphore");
                SemaphoreStep.success("two/2", null);
                SemaphoreStep.waitForStart("three/2", b);
                assertStepExecutions(e, "retry {}", "node {}", "semaphore");
                SemaphoreStep.success("three/2", null);
                SemaphoreStep.waitForStart("four/2", b);
                assertStepExecutions(e, "retry {}", "semaphore");
                SemaphoreStep.success("four/2", null);
                SemaphoreStep.waitForStart("five/1", b);
                assertStepExecutions(e, "semaphore");
                SemaphoreStep.success("five/1", null);
                SemaphoreStep.waitForStart("six/1", b);
                SemaphoreStep.waitForStart("seven/1", b);
                assertStepExecutions(e, "parallel {}", "node {}", "semaphore", "semaphore");
                SemaphoreStep.success("six/1", null);
                SemaphoreStep.success("seven/1", null);
                SemaphoreStep.waitForStart("eight/1", b);
                assertStepExecutions(e, "semaphore");
                SemaphoreStep.success("eight/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
                assertStepExecutions(e);
            }
        });
    }
    private static void assertStepExecutions(FlowExecution e, String... steps) throws Exception {
        List<String> current = stepNames(e.getCurrentExecutions(true));
        List<String> all = stepNames(e.getCurrentExecutions(false));
        int allCount = all.size();
        int blockCount = allCount - current.size();
        assertEquals(current + " was not the tail of " + all, current, all.subList(blockCount, allCount));
        ListIterator<String> it = all.listIterator();
        for (int i = 0; i < blockCount; i++) {
            it.set(it.next() + " {}");
        }
        assertEquals(Arrays.toString(steps), all.toString());
    }
    private static List<String> stepNames(ListenableFuture<List<StepExecution>> executionsFuture) throws Exception {
        List<String> r = new ArrayList<String>();
        for (StepExecution e : executionsFuture.get()) {
            // TODO should this method be defined in StepContext?
            StepDescriptor d = ((CpsStepContext) e.getContext()).getStepDescriptor();
            assertNotNull(d);
            r.add(d.getFunctionName());
        }
        return r;
    }

    @Issue("JENKINS-25736")
    @Test public void pause() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "echo 'before'; semaphore 'one';  echo 'after';"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("one/1", b);
                CpsFlowExecution e = (CpsFlowExecution) b.getExecution();
                e.pause(true);
                story.j.waitForMessage("before", b);
                SemaphoreStep.success("one/1", b);

                // not a very strong way of ensuring that the pause actually happens
                Thread.sleep(1000);
                assertTrue(b.isBuilding());

                e.pause(false);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }

    @Issue("JENKINS-26130")
    @Test public void interruptProgramLoad() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("def x = new " + BadThing.class.getCanonicalName() + "(); semaphore 'wait'", true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                Logger LOGGER = Logger.getLogger("org.jenkinsci.plugins.workflow");
                LOGGER.setLevel(Level.FINE);
                Handler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                LOGGER.addHandler(handler);
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getLastBuild();
                assertTrue(b.isBuilding());
                story.j.waitForMessage("Cannot restore BadThing", b);
                b.getExecutor().interrupt();
                story.j.assertBuildStatus(Result.ABORTED, story.j.waitForCompletion(b));
            }
        });
    }
    public static class BadThing {
        @Whitelisted public BadThing() {}
    }
    private static class BadThingPickle extends Pickle {
        @Override public ListenableFuture<?> rehydrate(final FlowExecutionOwner owner) {
            return new TryRepeatedly<BadThing>(1) {
                @Override protected BadThing tryResolve() throws Exception {
                    return null;
                }
                @Override protected FlowExecutionOwner getOwner() {
                    return owner;
                }
                @Override protected void printWaitingMessage(TaskListener listener) {
                    listener.getLogger().println("Cannot restore BadThing");
                }
            };
        }
    }
    @TestExtension("interruptProgramLoad") public static class BadThingPickleFactory extends SingleTypedPickleFactory<BadThing> {
        @Override protected Pickle pickle(BadThing object) {
            return new BadThingPickle();
        }
    }

}
