/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.SensitiveTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.deprecatedShape
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.core.smithy.renamedFrom
import software.amazon.smithy.rust.codegen.core.util.REDACTION
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.shouldRedact
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase

fun CodegenTarget.renderUnknownVariant() = when (this) {
    CodegenTarget.SERVER -> false
    CodegenTarget.CLIENT -> true
}

/**
 * Generate an `enum` for a Smithy Union Shape
 *
 * This generator will render a Rust enum representing [shape] when [render] is called. It will also render convenience
 * methods:
 * - `is_<variant>()`
 * - `as_<variant>()`
 *
 * for each variant.
 *
 * Finally, if `[renderUnknownVariant]` is true (the default), it will render an `Unknown` variant. This is used by
 * clients to allow response parsing to succeed, even if the server has added a new variant since the client was generated.
 */
class UnionGenerator(
    val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: RustWriter,
    private val shape: UnionShape,
    private val renderUnknownVariant: Boolean = true,
) {
    private val sortedMembers: List<MemberShape> = shape.allMembers.values.sortedBy { symbolProvider.toMemberName(it) }
    private val unionSymbol = symbolProvider.toSymbol(shape)

    fun render() {
        renderUnion()
        renderImplBlock()
        if (!unionSymbol.expectRustMetadata().derives.derives.contains(RuntimeType.Debug)) {
            if (shape.hasTrait<SensitiveTrait>()) {
                renderDebugImplForUnion()
            } else {
                renderDebugImplForUnionMemberWise()
            }
        }
    }

    private fun renderUnion() {
        writer.documentShape(shape, model)
        writer.deprecatedShape(shape)

        val containerMeta = unionSymbol.expectRustMetadata()
        containerMeta.render(writer)
        writer.rustBlock("enum ${unionSymbol.name}") {
            sortedMembers.forEach { member ->
                val memberSymbol = symbolProvider.toSymbol(member)
                val note =
                    memberSymbol.renamedFrom()?.let { oldName -> "This variant has been renamed from `$oldName`." }
                documentShape(member, model, note = note)
                deprecatedShape(member)
                memberSymbol.expectRustMetadata().renderAttributes(this)
                write("${symbolProvider.toMemberName(member)}(#T),", symbolProvider.toSymbol(member))
            }
            if (renderUnknownVariant) {
                docs("""The `Unknown` variant represents cases where new union variant was received. Consider upgrading the SDK to the latest available version.""")
                rust("/// An unknown enum variant")
                rust("///")
                rust("/// _Note: If you encounter this error, consider upgrading your SDK to the latest version._")
                rust("/// The `Unknown` variant represents cases where the server sent a value that wasn't recognized")
                rust("/// by the client. This can happen when the server adds new functionality, but the client has not been updated.")
                rust("/// To investigate this, consider turning on debug logging to print the raw HTTP response.")
                // at some point in the future, we may start actually putting things like the raw data in here.
                Attribute.NonExhaustive.render(this)
                rust("Unknown,")
            }
        }
    }

    private fun renderImplBlock() {
        writer.rustBlock("impl ${unionSymbol.name}") {
            sortedMembers.forEach { member ->
                val memberSymbol = symbolProvider.toSymbol(member)
                val funcNamePart = member.memberName.toSnakeCase()
                val variantName = symbolProvider.toMemberName(member)

                if (sortedMembers.size == 1) {
                    Attribute.Custom("allow(irrefutable_let_patterns)").render(this)
                }
                rust(
                    "/// Tries to convert the enum instance into [`$variantName`](#T::$variantName), extracting the inner #D.",
                    unionSymbol,
                    memberSymbol,
                )
                rust("/// Returns `Err(&Self)` if it can't be converted.")
                rustBlock("pub fn as_$funcNamePart(&self) -> std::result::Result<&#T, &Self>", memberSymbol) {
                    rust("if let ${unionSymbol.name}::$variantName(val) = &self { Ok(val) } else { Err(self) }")
                }
                rust("/// Returns true if this is a [`$variantName`](#T::$variantName).", unionSymbol)
                rustBlock("pub fn is_$funcNamePart(&self) -> bool") {
                    rust("self.as_$funcNamePart().is_ok()")
                }
            }
            if (renderUnknownVariant) {
                rust("/// Returns true if the enum instance is the `Unknown` variant.")
                rustBlock("pub fn is_unknown(&self) -> bool") {
                    rust("matches!(self, Self::Unknown)")
                }
            }
        }
    }

    private fun renderDebugImplForUnion() {
        writer.rustTemplate(
            """
            impl #{Debug} for ${unionSymbol.name} {
                fn fmt(&self, f: &mut #{StdFmt}::Formatter<'_>) -> #{StdFmt}::Result {
                    write!(f, $REDACTION)
                }
            }
            """,
            "Debug" to RuntimeType.Debug,
            "StdFmt" to RuntimeType.stdfmt,
        )
    }

    private fun renderDebugImplForUnionMemberWise() {
        writer.rustBlock("impl #T for ${unionSymbol.name}", RuntimeType.Debug) {
            writer.rustBlock("fn fmt(&self, f: &mut #1T::Formatter<'_>) -> #1T::Result", RuntimeType.stdfmt) {
                rustBlock("match self") {
                    sortedMembers.forEach { member ->
                        val memberName = symbolProvider.toMemberName(member)
                        if (member.shouldRedact(model)) {
                            rust("${unionSymbol.name}::$memberName(_) => f.debug_tuple($REDACTION).finish(),")
                        } else {
                            rust("${unionSymbol.name}::$memberName(val) => f.debug_tuple(${memberName.dq()}).field(&val).finish(),")
                        }
                    }
                    if (renderUnknownVariant) {
                        rust("${unionSymbol.name}::$UnknownVariantName => f.debug_tuple(${UnknownVariantName.dq()}).finish(),")
                    }
                }
            }
        }
    }

    companion object {
        const val UnknownVariantName = "Unknown"
    }
}

fun unknownVariantError(union: String) =
    "Cannot serialize `$union::${UnionGenerator.UnknownVariantName}` for the request. " +
        "The `Unknown` variant is intended for responses only. " +
        "It occurs when an outdated client is used after a new enum variant was added on the server side."
