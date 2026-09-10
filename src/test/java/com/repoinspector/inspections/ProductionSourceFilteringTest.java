package com.repoinspector.inspections;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.repoinspector.inspections.scan.RepoBuddyInspectionScanner;

import java.io.IOException;

/** Source-root regressions shared by editor inspections and project scanning. */
public class ProductionSourceFilteringTest extends LightJavaCodeInsightFixtureTestCase {

    private final RepoBuddyInspectionScanner scanner = new RepoBuddyInspectionScanner();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package jakarta.persistence; public interface EntityManager { void persist(Object value); }");
    }

    public void testProductionSource_isInspected() {
        PsiFile file = myFixture.configureByText("ProductionDao.java", invalidDao("ProductionDao"));
        assertMissingTransaction(file);
    }

    public void testProductionClassNamedSomethingTest_isStillInspected() {
        PsiFile file = myFixture.configureByText("SomethingTest.java", invalidDao("SomethingTest"));
        assertMissingTransaction(file);
    }

    public void testSrcTestJava_isIgnored() {
        PsiFile file = addToTestRoot("src/test/java", "TestDao.java", invalidDao("TestDao"));
        assertEmpty(scanner.scanFile(getProject(), file));
    }

    public void testCustomIntellijTestSourceRoot_isIgnored() {
        PsiFile file = addToTestRoot("verification-sources", "VerificationDao.java", invalidDao("VerificationDao"));
        assertEmpty(scanner.scanFile(getProject(), file));
    }

    public void testTestCallerCanResolveProductionCodeButGetsNoFinding() {
        myFixture.addFileToProject("ProductionService.java", "class ProductionService { void delete() {} }");
        PsiFile testFile = addToTestRoot("custom-tests", "ProductionServiceCaller.java",
                "class ProductionServiceCaller { void call(ProductionService service) { service.delete(); } }");
        assertEmpty(scanner.scanFile(getProject(), testFile));
    }

    private PsiFile addToTestRoot(String path, String fileName, String text) {
        try {
            VirtualFile root = myFixture.getTempDirFixture().findOrCreateDir(path);
            PsiTestUtil.addSourceRoot(getModule(), root, true);
            return myFixture.addFileToProject(path + "/" + fileName, text);
        } catch (IOException error) {
            throw new AssertionError("Unable to create test source root", error);
        }
    }

    private void assertMissingTransaction(PsiFile file) {
        assertTrue(scanner.scanFile(getProject(), file).stream()
                .anyMatch(finding -> finding.inspection().equals("Missing @Transactional")));
    }

    private static String invalidDao(String className) {
        return "import jakarta.persistence.EntityManager; class " + className
                + " { EntityManager em; void write(Object value) { em.persist(value); } }";
    }
}
