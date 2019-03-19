/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower.loops

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDoWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.util.OperatorNameConventions

/** Contains information about variables used in the loop. */
internal sealed class ForLoopHeader(
    val inductionVariable: IrVariable,
    val bound: IrVariable,
    val last: IrVariable,
    val step: IrVariable,
    val progressionType: ProgressionType
) {
    abstract fun initializeLoopVariable(symbols: Symbols<CommonBackendContext>, builder: DeclarationIrBuilder): IrExpression

    abstract val declarations: List<IrStatement>

    abstract fun buildBody(builder: DeclarationIrBuilder, loop: IrLoop, newBody: IrExpression?): IrLoop
}

internal class ProgressionLoopHeader(
    headerInfo: ProgressionHeaderInfo,
    inductionVariable: IrVariable,
    bound: IrVariable,
    last: IrVariable,
    step: IrVariable,
    var loopVariable: IrVariable? = null
) : ForLoopHeader(inductionVariable, bound, last, step, headerInfo.progressionType) {

    val closed = headerInfo.closed

    private val increasing = headerInfo.increasing

    fun comparingFunction(builtIns: IrBuiltIns) = if (increasing)
        builtIns.lessOrEqualFunByOperandType[builtIns.int]?.symbol!!
    else
        builtIns.greaterOrEqualFunByOperandType[builtIns.int]?.symbol!!

    override fun initializeLoopVariable(symbols: Symbols<CommonBackendContext>, builder: DeclarationIrBuilder) = with(builder) {
        irGet(inductionVariable)
    }

    override val declarations: List<IrStatement>
        get() = listOf(inductionVariable, step, bound, last)

    override fun buildBody(builder: DeclarationIrBuilder, loop: IrLoop, newBody: IrExpression?): IrLoop = with(builder) {
        assert(loopVariable != null)
        val newCondition = irCall(context.irBuiltIns.booleanNotSymbol).apply {
            putValueArgument(0, irCall(context.irBuiltIns.eqeqSymbol).apply {
                putValueArgument(0, irGet(loopVariable!!))
                putValueArgument(1, irGet(last))
            })
        }
        IrDoWhileLoopImpl(loop.startOffset, loop.endOffset, loop.type, loop.origin).apply {
            label = loop.label
            condition = newCondition
            body = newBody
        }
    }
}

internal class ArrayLoopHeader(
    headerInfo: ArrayHeaderInfo,
    inductionVariable: IrVariable,
    bound: IrVariable,
    last: IrVariable,
    step: IrVariable
) : ForLoopHeader(inductionVariable, bound, last, step, ProgressionType.INT_PROGRESSION) {

    private val arrayDeclaration = headerInfo.arrayVariable

    override fun initializeLoopVariable(symbols: Symbols<CommonBackendContext>, builder: DeclarationIrBuilder) = with(builder) {
        val arrayClass = (arrayDeclaration.type.classifierOrNull) as IrClassSymbol
        val arrayGetFun = arrayClass.owner.functions.find { it.name.toString() == "get" }!!
        irCall(arrayGetFun).apply {
            dispatchReceiver = irGet(arrayDeclaration)
            putValueArgument(0, irGet(inductionVariable))
        }
    }

    override val declarations: List<IrStatement>
        get() = listOf(arrayDeclaration, inductionVariable, step, bound, last)

    override fun buildBody(builder: DeclarationIrBuilder, loop: IrLoop, newBody: IrExpression?): IrLoop = with(builder) {
        val builtIns = context.irBuiltIns
        val callee = builtIns.lessOrEqualFunByOperandType[builtIns.int]?.symbol!!
        val newCondition = irCall(callee).apply {
            putValueArgument(0, irGet(inductionVariable))
            putValueArgument(1, irGet(last))
        }
        IrWhileLoopImpl(loop.startOffset, loop.endOffset, loop.type, loop.origin).apply {
            label = loop.label
            condition = newCondition
            body = newBody
        }
    }
}

// Given the for loop iterator variable, extract information about iterable subject
// and create ForLoopHeader from it.
internal class HeaderProcessor(
    private val context: CommonBackendContext,
    private val headerInfoBuilder: HeaderInfoBuilder,
    private val scopeOwnerSymbol: () -> IrSymbol
) {

    private val symbols = context.ir.symbols

    fun processHeader(variable: IrVariable): ForLoopHeader? {

        assert(variable.origin == IrDeclarationOrigin.FOR_LOOP_ITERATOR)
        if (!variable.type.isSubtypeOfClass(symbols.iterator)) {
            return null
        }
        val builder = context.createIrBuilder(scopeOwnerSymbol(), variable.startOffset, variable.endOffset)
        // Collect loop info and form the loop header composite.
        val progressionInfo = headerInfoBuilder.build(variable)
            ?: return null
        if (progressionInfo is ArrayHeaderInfo) {
            progressionInfo.arrayVariable.parent = variable.parent
        }
        with(builder) {
            with(progressionInfo) {
                /**
                 * For this loop:
                 * `for (i in a() .. b() step c() step d())`
                 * We need to call functions in the following order: a, b, c, d.
                 * So we call b() before step calculations and then call last element calculation function (if required).
                 */
                // Due to features of PSI2IR we can obtain nullable arguments here while actually
                // they are non-nullable (the frontend takes care about this). So we need to cast them to non-nullable.
                val inductionVariable = scope.createTemporaryVariable(
                    lowerBound.castIfNecessary(progressionType),
                    nameHint = "inductionVariable",
                    isMutable = true,
                    origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE
                )

                val upperBoundTmpVariable = scope.createTemporaryVariable(
                    ensureNotNullable(upperBound.castIfNecessary(progressionType)),
                    nameHint = "upperBound",
                    origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE
                )

                val stepExpression = ensureNotNullable(step)

                val stepValue = scope.createTemporaryVariable(
                    stepExpression,
                    nameHint = "step",
                    origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE
                )

                // Calculate the last element of the progression
                // The last element can be:
                //    boundValue, if step is 1 and the range is closed.
                //    boundValue - 1, if step is 1 and the range is open.
                //    getProgressionLast(inductionVariable, boundValue, step), if step != 1 and the range is closed.
                //    getProgressionLast(inductionVariable, boundValue - 1, step), if step != 1 and the range is open.
                val lastExpression = if (closed) {
                    irGet(upperBoundTmpVariable)
                } else {
                    val decrementSymbol = symbols.getUnaryOperator(OperatorNameConventions.DEC, upperBoundTmpVariable.type.toKotlinType())
                    irCall(decrementSymbol.owner).apply {
                        dispatchReceiver = irGet(upperBoundTmpVariable)
                    }
                }
                val lastValue = scope.createTemporaryVariable(
                    lastExpression,
                    nameHint = "last",
                    origin = IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE
                )

                return when (progressionInfo) {
                    is ArrayHeaderInfo -> ArrayLoopHeader(
                        progressionInfo,
                        inductionVariable,
                        upperBoundTmpVariable,
                        lastValue,
                        stepValue
                    )
                    is ProgressionHeaderInfo -> ProgressionLoopHeader(
                        progressionInfo,
                        inductionVariable,
                        upperBoundTmpVariable,
                        lastValue,
                        stepValue
                    )
                }
            }
        }
    }


    private fun IrExpression.castIfNecessary(progressionType: ProgressionType): IrExpression {
        return if (type.toKotlinType() == progressionType.elementType(context.irBuiltIns).toKotlinType()) {
            this
        } else {
            val function = type.getClass()!!.functions.first { it.name == progressionType.numberCastFunctionName }
            IrCallImpl(startOffset, endOffset, function.returnType, function.symbol)
                .apply { dispatchReceiver = this@castIfNecessary }
        }
    }

    private fun DeclarationIrBuilder.ensureNotNullable(expression: IrExpression) =
        if (expression.type is IrSimpleType && expression.type.isNullable()) {
            irImplicitCast(expression, expression.type.makeNotNull())
        } else {
            expression
        }
}