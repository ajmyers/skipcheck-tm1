/**
 * Import hygiene for Java sources, checked without compiling: unused imports, imports out of
 * order, and types written with their full package name in code where an import belongs.
 *
 * The order: static imports, then java and javax, then org, then everything else; alphabetical
 * within each group, and one blank line between groups. Spring Java Format doesn't order imports,
 * so this does.
 *
 * A text scan, not a parser, so it's deliberately conservative. Comments, string, text block and
 * character literals are blanked out before looking for names, and a fully qualified name is
 * allowed when the short name is taken by another import or a type the file declares (two classes
 * called HttpClient, say). Javadoc counts as a use of an import, since {@link Type} needs one.
 */
object JavaImports {

	/** A package-qualified type name: lowercase segments, then a capitalized one. */
	private val qualified = Regex("""\b(?:java|javax|jakarta|org|com|net|io|reactor|tools|lombok)\.(?:[a-z_][a-z0-9_]*\.)+[A-Z]\w*""")

	private val importLine = Regex("""^import\s+(static\s+)?([\w.]+)\s*;[ \t]*(\r?\n)?""", RegexOption.MULTILINE)

	private val packageLine = Regex("""^package\s+[\w.]+\s*;""", RegexOption.MULTILINE)

	private val declaredType = Regex("""\b(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)""")

	/** One problem, at a 1-based line. */
	data class Problem(val line: Int, val message: String)

	/** What's wrong with the imports of one file, and any Javadoc line that lost its leading {@code *}. */
	fun problems(source: String): List<Problem> {
		val problems = mutableListOf<Problem>()
		problems += brokenJavadoc(source)
		for (unused in unusedImports(source)) {
			problems += Problem(lineOf(source, unused.range.first), "unused import ${unused.groupValues[2]}")
		}
		if (!importsSorted(source)) {
			problems += Problem(lineOf(source, importBlock(source)!!.range.first), "imports aren't in order")
		}
		val imported = importLine.findAll(source).filter { it.groupValues[1].isEmpty() }.map { it.groupValues[2] }.toList()
		val declared = declaredType.findAll(blank(source)).map { it.groupValues[1] }.toSet()
		val code = blank(source).lines()
		code.forEachIndexed { index, line ->
			val trimmed = line.trim()
			if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
				return@forEachIndexed
			}
			for (match in qualified.findAll(line)) {
				val name = match.value
				val simple = name.substringAfterLast('.')
				val clash = simple in declared || imported.any { it.substringAfterLast('.') == simple && it != name }
				if (!clash) {
					problems += Problem(index + 1, "fully qualified $name: import it and write $simple")
				}
			}
		}
		return problems.sortedBy { it.line }
	}

	/**
	 * The source with its imports in order. A file with a comment among its imports is left as it
	 * is, since there's no telling which import the comment belongs to.
	 */
	fun sortImports(source: String): String {
		val block = importBlock(source) ?: return source
		val lines = block.value.lines().filter { it.isNotBlank() }
		if (lines.any { !it.trimStart().startsWith("import ") }) {
			return source
		}
		val sorted = lines.map { it.trim() }
			.distinct()
			.groupBy { group(it) }
			.toSortedMap()
			.values
			.joinToString("\n\n") { group -> group.sortedBy { it.removeSuffix(";") }.joinToString("\n") }
		return source.replaceRange(block.range, sorted)
	}

	/** Whether the imports are in order (see {@link sortImports}). */
	fun importsSorted(source: String) = sortImports(source) == source

	private fun group(line: String): Int {
		val name = line.removePrefix("import ").trim()
		return when {
			name.startsWith("static ") -> 0
			name.startsWith("java.") || name.startsWith("javax.") -> 1
			name.startsWith("org.") -> 2
			else -> 3
		}
	}

	/** From the first import line to the end of the last one. */
	private fun importBlock(source: String): MatchResult? {
		val first = Regex("""^import\s""", RegexOption.MULTILINE).find(source) ?: return null
		val last = importLine.findAll(source).lastOrNull() ?: return null
		val end = source.indexOf(';', last.range.first) + 1
		return Regex("""[\s\S]*""").matchAt(source.substring(0, end), first.range.first)
	}

	/**
	 * Lines inside a Javadoc comment that don't start with {@code *}. The formatter makes these
	 * out of an {@code @} at the start of a line in a code example, which it takes for a block
	 * tag: write {@code &#64;} in a {@code <pre class="code">} example instead.
	 */
	private fun brokenJavadoc(source: String): List<Problem> {
		val problems = mutableListOf<Problem>()
		for (comment in Regex("""/\*\*[\s\S]*?\*/""").findAll(source)) {
			val start = lineOf(source, comment.range.first)
			comment.value.lines().drop(1).forEachIndexed { index, line ->
				if (!line.trimStart().startsWith("*")) {
					problems += Problem(start + index + 1, "a Javadoc line without its leading *, broken by the formatter: write @ in code examples as &#64; in <pre class=\"code\">")
				}
			}
		}
		return problems
	}

	/** The source without its unused imports. */
	fun removeUnusedImports(source: String): String {
		var result = source
		for (unused in unusedImports(source).sortedByDescending { it.range.first }) {
			result = result.removeRange(unused.range)
		}
		return result
	}

	private fun unusedImports(source: String): List<MatchResult> {
		val imports = importLine.findAll(source).toList()
		// Everything but the import and package lines, comments included: Javadoc links use imports
		val rest = packageLine.replace(importLine.replace(source, ""), "")
		return imports.filter { import ->
			val simple = import.groupValues[2].substringAfterLast('.')
			simple != "*" && !Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(rest)
		}
	}

	/** The source with comments and literals blanked out, keeping every line where it was. */
	private fun blank(source: String): String {
		val keepLines = { m: MatchResult -> "\n".repeat(m.value.count { it == '\n' }) }
		var text = Regex("\"\"\"[\\s\\S]*?\"\"\"").replace(source, keepLines)
		text = Regex("""/\*[\s\S]*?\*/""").replace(text, keepLines)
		text = Regex("""'(?:\\.|[^'\\\n])'""").replace(text, "' '")
		text = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"").replace(text, "\"\"")
		text = Regex("""//[^\n]*""").replace(text, "")
		return text
	}

	private fun lineOf(source: String, offset: Int) = source.substring(0, offset).count { it == '\n' } + 1
}
