import importlib.util
import unittest

from extract import extract_markdown


@unittest.skipUnless(importlib.util.find_spec("lxml") and importlib.util.find_spec("markdownify"),
                     "DOM conversion dependencies are optional in ordinary stdlib CI")
class ExtractTests(unittest.TestCase):
    def test_structured_dom_retains_absolute_links_literal_math_and_code_indentation(self):
        html = r'''<html><head><title>Synthetic page</title></head><body><nav>NOISE</nav><main>
        <h1>Synthetic heading</h1><p>This synthetic article contains sufficient material for main extraction.</p>
        <a href="../paper.pdf">Paper</a><p>$$
x_i = \sum_j y_j
$$</p><pre><code class="language-python">def value():
    return 7</code></pre><ul><li>One</li></ul></main></body></html>'''
        title, markdown, truncated = extract_markdown(html, "https://example.com/docs/article", 100000)
        self.assertEqual("Synthetic page", title)
        self.assertIn("https://example.com/paper.pdf", markdown)
        self.assertIn("x_i = \\sum_j y_j", markdown)
        self.assertIn("```python\ndef value():\n    return 7\n```", markdown)
        self.assertNotIn("NOISE", markdown)
        self.assertFalse(truncated)

    def test_maximum_does_not_split_a_protected_formula_or_code_block(self):
        for html in [r"<html><body><p>before</p><p>$$ x_i = y_i $$</p></body></html>",
                     "<html><body><p>before</p><pre>synthetic code body</pre></body></html>"]:
            with self.subTest(html=html):
                _, markdown, truncated = extract_markdown(html, "https://example.com", 12)
                self.assertTrue(truncated)
                self.assertLessEqual(len(markdown), 12)
                self.assertNotIn("$$", markdown)
                self.assertNotIn("```", markdown)

    def test_katex_display_ancestor_and_nested_math_keep_display_semantics(self):
        for outer, math_display in [("katex-display", ""), ("", ' display="block"')]:
            html = ('<html><body><span class="' + outer + '"><span class="katex"><math' + math_display
                    + '><semantics><annotation encoding="application/x-tex">x_i = y_i</annotation></semantics>'
                      '</math></span></span></body></html>')
            with self.subTest(outer=outer, math_display=math_display):
                _, markdown, truncated = extract_markdown(html, "https://example.com", 100000)
                self.assertEqual("$$\nx_i = y_i\n$$", markdown)
                self.assertFalse(truncated)

    def test_document_base_url_is_used_for_relative_links(self):
        _, markdown, _ = extract_markdown('<html><head><base href="/docs/"></head><body><a href="part">Next</a></body></html>',
                                         "https://example.com/article", 100000)
        self.assertIn("https://example.com/docs/part", markdown)


if __name__ == "__main__":
    unittest.main()
