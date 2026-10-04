package org.dyh.learnhub.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OmmlLatexConverterTest {
    private final OmmlLatexConverter converter = new OmmlLatexConverter();

    static String run(String text) { return "<m:r><m:t>" + text + "</m:t></m:r>"; }
    private OmmlLatexConverter.Result convert(String contents) throws Exception {
        String xml = "<m:oMath xmlns:m=\"" + OmmlLatexConverter.M + "\">" + contents + "</m:oMath>";
        return converter.convert(WordLayoutExtractor.secureParse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement());
    }

    @Test void fractionsRadicalsAndCombinedScriptsPreserveTreeGrouping() throws Exception {
        var result = convert("<m:f><m:num><m:sSubSup><m:e>" + run("x+y") + "</m:e><m:sub>" + run("i")
                + "</m:sub><m:sup>" + run("2") + "</m:sup></m:sSubSup></m:num><m:den><m:rad><m:deg>"
                + run("3") + "</m:deg><m:e>" + run("z") + "</m:e></m:rad></m:den></m:f>");
        assertEquals("\\frac{{x+y}_{i}^{2}}{\\sqrt[{3}]{z}}", result.latex());
        assertEquals("restored", result.status());
        assertTrue(result.originalText().contains("x+y"));
    }

    @Test void sumAndIntegralRecoverLimitsFromActualOmml() throws Exception {
        var result = convert("<m:nary><m:naryPr><m:chr m:val=\"∑\"/></m:naryPr><m:sub>" + run("i=1")
                + "</m:sub><m:sup>" + run("n") + "</m:sup><m:e>" + run("a") + "</m:e></m:nary>"
                + "<m:nary><m:naryPr><m:chr m:val=\"∫\"/></m:naryPr><m:sub>" + run("0")
                + "</m:sub><m:sup>" + run("∞") + "</m:sup><m:e>" + run("f(x)dx") + "</m:e></m:nary>");
        assertEquals("\\sum _{i=1}^{n}{a}\\int _{0}^{\\infty }{f(x)dx}", result.latex());
        assertEquals("restored", result.status());
    }

    @Test void matrixAndDelimitersRecoverRowsColumnsAndInvisibleBoundary() throws Exception {
        var result = convert("<m:d><m:dPr><m:begChr m:val=\"{\"/><m:endChr m:val=\"\"/></m:dPr><m:e>"
                + "<m:m><m:mr><m:e>" + run("a") + "</m:e><m:e>" + run("b") + "</m:e></m:mr>"
                + "<m:mr><m:e>" + run("c") + "</m:e><m:e>" + run("d") + "</m:e></m:mr></m:m></m:e></m:d>");
        assertEquals("\\left\\{ \\begin{matrix}a & b \\\\ c & d\\end{matrix}\\right.", result.latex());
        assertEquals("restored", result.status());
    }

    @Test void accentsBarsAndFunctionsBecomeSupportedMathCommands() throws Exception {
        var result = convert("<m:acc><m:accPr><m:chr m:val=\"⃗\"/></m:accPr><m:e>" + run("v") + "</m:e></m:acc>"
                + "<m:bar><m:barPr><m:pos m:val=\"bot\"/></m:barPr><m:e>" + run("x") + "</m:e></m:bar>"
                + "<m:func><m:fName>" + run("sin") + "</m:fName><m:e>" + run("θ") + "</m:e></m:func>");
        assertEquals("\\vec{v}\\underline{x}\\sin{\\theta }", result.latex());
        assertEquals("restored", result.status());
    }

    @Test void ommlBarDefaultsToBottomAndNoBarFractionDoesNotInventParentheses() throws Exception {
        var result = convert("<m:bar><m:e>" + run("x") + "</m:e></m:bar>"
                + "<m:f><m:fPr><m:type m:val=\"noBar\"/></m:fPr><m:num>" + run("n") + "</m:num><m:den>" + run("k") + "</m:den></m:f>");
        assertEquals("\\underline{x}\\genfrac{}{}{0pt}{}{n}{k}", result.latex());
        assertEquals("restored", result.status());
    }

    @Test void functionNameWithUnsupportedStructureIsNotSilentlyFlattened() throws Exception {
        var result = convert("<m:func><m:fName><m:unknown>" + run("sin") + "</m:unknown></m:fName><m:e>" + run("x") + "</m:e></m:func>");
        assertEquals("partial", result.status());
        assertTrue(result.message().contains("unknown"));
        assertTrue(result.latex().contains("sin"));
    }

    @Test void specialCharactersCannotInjectLatexCommands() throws Exception {
        var result = convert(run("\\input{secret}#%$&amp;_"));
        assertEquals("\\backslash input\\{secret\\}\\#\\%\\$\\&\\_", result.latex());
        assertFalse(result.latex().contains("\\input"));
    }

    @Test void normalTextInsideEquationIsEscapedBeforeEnteringTextCommand() throws Exception {
        var result = convert("<m:r><m:rPr><m:nor/></m:rPr><m:t>label \\input{secret}&amp; cost $5</m:t></m:r>");
        assertEquals("\\text{label \\textbackslash{}input\\{secret\\}\\& cost \\$5}", result.latex());
        assertFalse(result.latex().contains("\\input"));
        assertEquals("restored", result.status());
    }

    @Test void unknownStructurePreservesContentAndMarksPartialInsteadOfPretendingExact() throws Exception {
        var result = convert("<m:unknownLayout><m:e>" + run("important") + "</m:e></m:unknownLayout>");
        assertEquals("important", result.latex());
        assertEquals("partial", result.status());
        assertTrue(result.message().contains("unknownLayout"));
        assertEquals("important", result.originalText());
    }

    @Test void unknownStructureWithDirectTextStillPreservesCharacters() throws Exception {
        var result = convert("<m:unknownLayout>do not lose this</m:unknownLayout>");
        assertEquals("do\\ not\\ lose\\ this", result.latex());
        assertEquals("do not lose this", result.originalText());
        assertEquals("partial", result.status());
    }

    @Test void missingDenominatorIsExplicitlyPartial() throws Exception {
        var result = convert("<m:f><m:num>" + run("a") + "</m:num></m:f>");
        assertEquals("\\frac{a}{}", result.latex());
        assertEquals("partial", result.status());
        assertTrue(result.message().contains("den"));
    }

    @Test void excessiveMathNestingReturnsUnavailableWithOriginalTextRatherThanCrashing() throws Exception {
        var result = convert("<m:e>".repeat(100) + run("x") + "</m:e>".repeat(100));
        assertNull(result.latex());
        assertEquals("unavailable", result.status());
        assertTrue(result.message().contains("过深"));
        assertTrue(result.originalText().contains("超限"));
    }

    @Test void nullOrEmptyEquationHasAnExplicitUnavailableStatus() throws Exception {
        assertEquals("unavailable", converter.convert(null).status());
        assertEquals("unavailable", convert("").status());
    }

    @Test void oversizedEquationReturnsExplicitUnavailableAndRetainsBoundedOriginal() throws Exception {
        var result = convert(run("x".repeat(40_000)));
        assertEquals("unavailable", result.status());
        assertNull(result.latex());
        assertTrue(result.originalText().contains("超过上限"));
        assertTrue(result.originalText().length() < 33_000);
    }
}
