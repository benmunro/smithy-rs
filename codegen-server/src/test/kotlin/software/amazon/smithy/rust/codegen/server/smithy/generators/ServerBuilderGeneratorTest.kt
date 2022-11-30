/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.EnumShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.implBlock
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.core.util.toPascalCase
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestSymbolProvider

class ServerBuilderGeneratorTest {
    @Test
    fun `generate default required values not set by user`() {
        val valuesMap = generateValues(set_by_user = false).toMutableMap()
        val model = generateModel(required = true, nullDefault = false, valuesMap)
        val symbolProvider = serverTestSymbolProvider(model)
        val project = TestWorkspace.testProject(symbolProvider)
        project.withModule(RustModule.public("model")) {
            writeRustFiles(this, model, symbolProvider)

            setupValuesForTest(valuesMap)
            unitTest(
                name = "generates default required values".replace(" ", "_"),
                test = """
                    let my_struct = MyStruct::builder()
                        .test_test_document_null(false.into())
                        .build().unwrap();
                    assert_eq!(my_struct.test_test_document_null, false.into());
                """ + assertions(valuesMap, optional = false)
                    .replace("assert_eq!.my_struct.test_test_document_null[^;]+".toRegex(), ""),
            )
            project.compileAndTest()
        }
    }

    @Test
    fun `generate default required values set by user`() {
        val valuesMap = generateValues(set_by_user = false).toMutableMap()
        val model = generateModel(required = true, nullDefault = false, valuesMap)
        val symbolProvider = serverTestSymbolProvider(model)
        val project = TestWorkspace.testProject(symbolProvider)
        project.withModule(RustModule.public("model")) {
            val valuesMap = generateValues(set_by_user = true).toMutableMap()
            writeRustFiles(this, model, symbolProvider)

            setupValuesForTest(valuesMap)
            unitTest(
                name = "generates and sets default required values".replace(" ", "_"),
                test = """
                    let my_struct = MyStruct::builder()
                """ + structSetters(valuesMap) +
                    """
                    .build().unwrap();
                    """ + assertions(valuesMap, optional = false),
            )

            project.compileAndTest()
        }
    }

    @Test
    fun `generate default non required null values not set by user`() {
        val valuesMap = generateValues(set_by_user = false).toMutableMap()
        val model = generateModel(required = false, nullDefault = true, valuesMap)
        val symbolProvider = serverTestSymbolProvider(model)
        val project = TestWorkspace.testProject(symbolProvider)
        project.withModule(RustModule.public("model")) {
            writeRustFiles(this, model, symbolProvider)

            setupValuesForTest(valuesMap)
            unitTest(
                name = "generates default required values".replace(" ", "_"),
                test = """
                    let my_struct = MyStruct::builder().build();
                """ + assertions(valuesMap, optional = true),
            )
            project.compileAndTest()
        }
    }

    @Test
    fun `generate default non required null values set by user`() {
        val valuesMap = generateValues(set_by_user = false).toMutableMap()
        val model = generateModel(required = true, nullDefault = true, valuesMap)
        val symbolProvider = serverTestSymbolProvider(model)
        val project = TestWorkspace.testProject(symbolProvider)
        project.withModule(RustModule.public("model")) {
            val valuesMap = generateValues(set_by_user = true).toMutableMap()
            writeRustFiles(this, model, symbolProvider)

            setupValuesForTest(valuesMap)
            unitTest(
                name = "generates and sets default required values".replace(" ", "_"),
                test = """
                    let my_struct = MyStruct::builder()
                """ + structSetters(valuesMap) +
                    """
                    .build().unwrap();
                    """ + assertions(valuesMap, optional = false),
            )

            project.compileAndTest()
        }
    }

    private fun setupValuesForTest(valuesMap: MutableMap<String, String>) {
        valuesMap["Language"] = "crate::model::Language::${valuesMap["Language"]!!.replace(""""""", "").toPascalCase()}"
        valuesMap["Timestamp"] =
            """aws_smithy_types::DateTime::from_str(${valuesMap["Timestamp"]}, aws_smithy_types::date_time::Format::DateTime).unwrap()"""
        valuesMap["StringList"] = "Vec::<String>::new()"
        valuesMap["StringIntegerMap"] = "std::collections::HashMap::<String, i32>::new()"
        valuesMap["TestDocumentList"] = "Vec::<aws_smithy_types::Document>::new()"
        valuesMap["TestDocumentMap"] = "std::collections::HashMap::<String, aws_smithy_types::Document>::new()"
    }

    private fun writeRustFiles(writer: RustWriter, model: Model, symbolProvider: RustSymbolProvider) {
        writer.rust("##![allow(deprecated)]")
        val struct = model.lookup<StructureShape>("com.test#MyStruct")
        val generator = StructureGenerator(model, symbolProvider, writer, struct)
        val codegenContext = serverTestCodegenContext(model)
        val builderGenerator = ServerBuilderGenerator(codegenContext, struct)

        generator.render()
        builderGenerator.render(writer)
        writer.implBlock(struct, symbolProvider) {
            builderGenerator.renderConvenienceMethod(writer)
        }
        ServerEnumGenerator(codegenContext, writer, model.lookup<EnumShape>("com.test#Language")).render()
    }

    private fun structSetters(values: Map<String, String>): String {
        return values.entries.joinToString("\n") {
            if (it.key == "String") {
                ".test_${it.key.toSnakeCase()}(${it.value}.into())"
            } else if (it.key == "TestDocumentNull") {
                ".test_${it.key.toSnakeCase()}(aws_smithy_types::Document::Null)"
            } else if (it.value == "null") {
                "*/ not setting test_${it.key.toSnakeCase()} */"
            } else if (it.key.startsWith("TestDocumentNumber")) {
                val type = it.key.replace("TestDocumentNumber", "")
                ".test_${it.key.toSnakeCase()}(aws_smithy_types::Document::Number(aws_smithy_types::Number::$type(${it.value})))"
            } else if (it.key == "TestDocumentString") {
                ".test_${it.key.toSnakeCase()}(String::from(${it.value}).into())"
            } else if (it.key.startsWith("TestDocument")) {
                ".test_${it.key.toSnakeCase()}(${it.value}.into())"
            } else {
                ".test_${it.key.toSnakeCase()}(${it.value})"
            }
        }
    }

    private fun assertions(values: Map<String, String>, optional: Boolean): String {
        return values.entries
            .joinToString("\n") {
                """
                assert_eq!(my_struct.test_${it.key.toSnakeCase()} ${
                if (optional) {
                    ".is_none(), true"
                } else if (it.key == "TestDocumentNull") {
                    ", aws_smithy_types::Document::Null"
                } else if (it.key == "TestDocumentString") {
                    ", String::from(${it.value}).into()"
                } else if (it.key.startsWith("TestDocumentNumber")) {
                    val type = it.key.replace("TestDocumentNumber", "")
                    ", aws_smithy_types::Document::Number(aws_smithy_types::Number::$type(${it.value}))"
                } else if (it.key.startsWith("TestDocument")) {
                    ", ${it.value}.into()"
                } else {
                    ", " + it.value
                }
                });
                """
            }
    }

    private fun generateValues(set_by_user: Boolean): Map<String, String> {
        return mutableMapOf(
            "Boolean" to if (set_by_user) "true" else "false",
            "String" to if (set_by_user) "foo".dq() else "bar".dq(),
            "Byte" to if (set_by_user) "5" else "6",
            "Short" to if (set_by_user) "55" else "66",
            "Integer" to if (set_by_user) "555" else "666",
            "Long" to if (set_by_user) "5555" else "6666",
            "Float" to if (set_by_user) "0.5" else "0.6",
            "Double" to if (set_by_user) "0.55" else "0.66",
            "Timestamp" to if (set_by_user) "1985-04-12T23:20:50.52Z".dq() else "2022-11-25T17:30:50.00Z".dq(),
//            "BigInteger" to "55555", "BigDecimal" to "0.555", // unsupported
            "StringList" to "[]",
            "StringIntegerMap" to "{}",
            "Language" to if (set_by_user) "en".dq() else "fr".dq(),
            "TestDocumentNull" to "null",
            "TestDocumentBoolean" to if (set_by_user) "true" else "false",
            "TestDocumentString" to if (set_by_user) "foo".dq() else "bar".dq(),
            "TestDocumentNumberPosInt" to if (set_by_user) "100" else "1000",
            "TestDocumentNumberNegInt" to if (set_by_user) "-100" else "-1000",
            "TestDocumentNumberFloat" to if (set_by_user) "0.1" else "0.01",
            "TestDocumentList" to "[]",
            "TestDocumentMap" to "{}",
        )
    }

    private fun generateModel(required: Boolean, nullDefault: Boolean, values: Map<String, String>): Model {
        val requiredTrait = if (required) "@required" else ""

        val model =
            """
            namespace com.test
            use smithy.framework#ValidationException

            structure MyStruct {
            """ +
                values.entries.joinToString(", ") {
                    """
                    $requiredTrait
                    test${it.key.toPascalCase()}: ${it.key} = ${if (nullDefault) "null" else it.value}
                    """
                } +
                """
                }

                enum Language {
                    EN = "en",
                    FR = "fr",
                }

                list StringList {
                    member: String
                }

                map StringIntegerMap {
                    key: String
                    value: Integer
                }

                @default(true)
                document TestDocumentNull
                document TestDocumentBoolean
                document TestDocumentString
                document TestDocumentDecimal
                document TestDocumentNumberNegInt
                document TestDocumentNumberPosInt
                document TestDocumentNumberFloat
                document TestDocumentList
                document TestDocumentMap

                """
        return model.asSmithyModel(smithyVersion = "2")
    }
}
