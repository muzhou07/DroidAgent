package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.llm.ChatMessage

object TokenEstimator {
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x2E80..0x9FFF || ch.code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + (other / 4) + 1
    }

    fun estimateMessage(message: ChatMessage): Int {
        var total = 6 + estimateText(message.plainText())
        message.toolCalls?.forEach { call ->
            total += 8 + estimateText(call.function.name) + estimateText(call.function.arguments)
        }
        return total
    }

    fun estimateMessages(messages: List<ChatMessage>): Int = messages.sumOf { estimateMessage(it) }
}
