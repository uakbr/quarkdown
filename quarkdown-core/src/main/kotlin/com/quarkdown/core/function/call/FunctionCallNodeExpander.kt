package com.quarkdown.core.function.call

import com.quarkdown.core.ast.Node
import com.quarkdown.core.ast.quarkdown.FunctionCallNode
import com.quarkdown.core.context.MutableContext
import com.quarkdown.core.function.value.output.OutputValueVisitorFactory
import com.quarkdown.core.function.value.output.node.NodeOutputValueVisitorFactory
import com.quarkdown.core.pipeline.error.PipelineErrorHandler
import com.quarkdown.core.pipeline.error.PipelineException
import com.quarkdown.core.pipeline.error.asNode

/**
 * Given a [FunctionCallNode] from the AST, this expander resolves its referenced function, executes it
 * and maps its result to a visible output in the final document.
 * @param context root context to dequeue to-be-expanded function calls from
 * @param errorHandler strategy to handle errors that may occur during the execution of a function call
 * @param outputMapperFactory producer of an AST output [Node] from the function call output
 */
class FunctionCallNodeExpander(
    private val context: MutableContext,
    private val errorHandler: PipelineErrorHandler,
    private val outputMapperFactory: OutputValueVisitorFactory<Node> = NodeOutputValueVisitorFactory(context),
) {
    // Output-to-node mappers, for block and inline function calls respectively.
    private val blockMapper = outputMapperFactory.block()
    private val inlineMapper = outputMapperFactory.inline()

    /**
     * Resolves, executes and stores the result of [node]'s referenced function.
     * @param node AST function call node to expand
     */
    private fun expand(node: FunctionCallNode) {
        if (node.children.isNotEmpty()) {
            // The function call has already been expanded: do nothing.
            return
        }

        // The function call node is used to retrieve its corresponding function call.
        // By resolving it from the node's context instead of the root one,
        // we make sure to call it from the correct scope, hence providing the needed environment.
        val call: UncheckedFunctionCall<*> = node.context.resolveUnchecked(node)

        try {
            // The result of the function is converted into a node to be appended to the AST.
            // The value-to-node mapper used depends on whether the function call is block or inline.
            val mapper = if (node.isBlock) blockMapper else inlineMapper
            val outputNode = call.execute().accept(mapper)
            appendOutput(node, outputNode)
        } catch (e: PipelineException) {
            // If the function call is invalid, shows an error box.
            appendOutput(node, e.asNode(errorHandler))
        }
    }

    /**
     * Adds [output] to [call]'s content in the AST.
     * @param call function call node
     * @param output output node to append
     */
    private fun appendOutput(
        call: FunctionCallNode,
        output: Node,
    ) {
        call.children += output
    }

    /**
     * Expands all unexpanded function calls present in [context], and empties queued function calls in [context].
     * This is performed on a copy of the context's execution queue to avoid `ConcurrentModificationException`.
     * Hence, if a function call is added during the expansion, [expandAll] must be called again.
     */
    fun expandAll() {
        val calls = context.dequeueAllFunctionCalls()
        calls.forEach { expand(it) }
    }
}
