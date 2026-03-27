package ai.plyxal.ijmcp.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.nio.file.Files

class IjMcpSearchToolHandlersTest : BasePlatformTestCase() {
    fun testSearchFilesReturnsProjectScopedMatches() {
        myFixture.addFileToProject(
            "src/main/java/example/NestedWidget.java",
            """
            package example;

            class NestedWidget {
                int count;

                void computeValue() {
                }
            }
            """.trimIndent(),
        )

        val result = handler("search_files").call(
            buildJsonObject {
                put("query", "NestedWidget.java")
            },
        )

        assertFalse(result.isError)
        val structured = result.structuredContent
        val firstResult = structured.getValue("results").jsonArray.first().jsonObject
        val returnedPath = firstResult.getValue("path").jsonPrimitive.content

        assertEquals("success", structured.getValue("status").jsonPrimitive.content)
        assertEquals(project.name, structured.getValue("projectName").jsonPrimitive.content)
        assertTrue(returnedPath.endsWith("src/main/java/example/NestedWidget.java"))
        assertEquals("exact", firstResult.getValue("matchKind").jsonPrimitive.content)
    }

    fun testSearchFilesRejectsInvalidLimit() {
        val result = handler("search_files").call(
            buildJsonObject {
                put("query", "NestedWidget")
                put("limit", 0)
            },
        )

        assertTrue(result.isError)
        assertEquals("invalid_tool_arguments", result.structuredContent.getValue("errorCode").jsonPrimitive.content)
    }

    fun testSearchSymbolsReturnsProjectScopedMatches() {
        myFixture.addFileToProject(
            "src/main/java/example/NestedWidget.java",
            """
            package example;

            class NestedWidget {
                void computeValue() {
                }
            }
            """.trimIndent(),
        )

        val result = handler("search_symbols").call(
            buildJsonObject {
                put("query", "computeValue")
            },
        )

        assertFalse(result.isError)
        val structured = result.structuredContent
        val firstResult = structured.getValue("results").jsonArray.first().jsonObject
        val returnedPath = firstResult.getValue("path").jsonPrimitive.content

        assertEquals("success", structured.getValue("status").jsonPrimitive.content)
        assertEquals("computeValue", firstResult.getValue("symbolName").jsonPrimitive.content)
        assertTrue(returnedPath.endsWith("src/main/java/example/NestedWidget.java"))
    }

    fun testSearchSymbolsReturnStableMissError() {
        val result = handler("search_symbols").call(
            buildJsonObject {
                put("query", "DoesNotExistAnywhere")
            },
        )

        assertTrue(result.isError)
        assertEquals("symbol_not_found", result.structuredContent.getValue("errorCode").jsonPrimitive.content)
    }

    fun testPathResolverRejectsOutsideProjectFiles() {
        val outsideFile = Files.createTempFile("ijmcp-outside-project", ".txt")
        Files.writeString(outsideFile, "outside project")

        val resolution = IjMcpPathResolver().resolveFile(project, outsideFile.toString())

        assertTrue(resolution is IjMcpResolvedFileResult.Failure)
        val failure = resolution as IjMcpResolvedFileResult.Failure
        assertEquals("outside_project", failure.errorCode)
    }

    private fun handler(name: String) = IjMcpSearchToolHandlers(project)
        .all()
        .associateBy { it.descriptor.name }
        .getValue(name)
}
