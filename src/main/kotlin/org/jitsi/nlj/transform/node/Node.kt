/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.Stoppable
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.nlj.util.Util.Companion.getMbps
import org.jitsi.nlj.util.getLogger
import java.time.Duration
import kotlin.properties.Delegates

interface NodeVisitor {
    fun visit(node: Node)
}

class NodeStatsVisitor(val nodeStatsBlock: NodeStatsBlock) : NodeVisitor {
    override fun visit(node: Node) {
        val block = node.getNodeStats()
        nodeStatsBlock.addStat(block.name, block)
    }
}

class NodeEventVisitor(val event: Event) : NodeVisitor {
    override fun visit(node: Node) {
        node.handleEvent(event)
    }
}

/**
 * An abstract base class for all [Node] subclasses.  This class
 * takes care of the following behaviors:
 * 1) Attaching the next node in the chain
 * 2) Basic stat tracking (time duration, packets in/out, bytes in/out,
 * throughput, etc.)
 * 3) Propagating [visit] calls
 *
 */
abstract class Node(
    var name: String
) : PacketHandler, EventHandler, NodeStatsProducer, Stoppable {
    private var nextNode: Node? = null
    private val inputNodes: MutableList<Node> by lazy { mutableListOf<Node>() }
    // Create these once here so we don't allocate a new string every time
    private val nodeEntryString = "Entered node $name"
    private val nodeExitString = "Exited node $name"

    protected val logger = getLogger(this.javaClass)

    // Stats stuff
    private var startTime: Long = 0
    private var totalProcessingDuration: Long = 0
    private var numInputPackets = 0
    private var numOutputPackets = 0
    private var firstPacketTime: Long = -1
    private var lastPacketTime: Long = -1
    private var numBytes: Long = 0

    open fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
        nextNode?.visit(visitor)
    }

    /**
     * [reverseVisit] is used for traversing an 'outgoing'-style tree which
     * has many input paths but terminates at a single point (as opposed to an
     * 'incoming'-style tree which starts at a single point and then branches
     * into multiple paths.  With reverseVisit, we start at the single terminating
     * point and traverse backwards through the tree.  It should be noted, however,
     * that reverseVisit is done in a 'postorder' traversal style (meaning that a [Node]'s
     * 'inputNodes' are visited before that Node itself.
     * TODO: protect against visiting the same node twice in the event of a cycle (which
     * we should do for 'visit' as well)
     */
    open fun reverseVisit(visitor: NodeVisitor) {
        inputNodes.forEach { it.reverseVisit(visitor) }
        visitor.visit(this)
    }
    /**
     * The function that all subclasses should implement to do the actual
     * packet processing.  A protected method is used for this so we can
     * guarantee all packets pass through this base for stat-tracking
     * purposes.
     */
    protected abstract fun doProcessPackets(p: List<PacketInfo>)

    /**
     * Marking this as open since Demuxer wants to throw an exception
     * if attach is called.
     */
    open fun attach(node: Node) {
        // Remove ourselves as an input from the node we're currently connected to
        nextNode?.inputNodes?.remove(this)
        nextNode = node
        node.inputNodes.add(this)
    }

    override fun processPackets(pkts: List<PacketInfo>) {
        onEntry(pkts)
        doProcessPackets(pkts)
    }

    override fun handleEvent(event: Event) {
        // No-op by default
    }

    override fun stop() {
        // No-op by default
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Node $name ${hashCode()}").apply {
            addStat("numInputPackets: $numInputPackets")
            addStat("numOutputPackets: $numOutputPackets")
            addStat("total time spent: ${Duration.ofNanos(totalProcessingDuration).toMillis()} ms")
            addStat("average time spent per packet: ${Duration.ofNanos(totalProcessingDuration / Math.max(numInputPackets, 1)).toMillis()} ms")
            addStat("$numBytes bytes over ${Duration.ofNanos(lastPacketTime - firstPacketTime).toMillis()} ms")
            addStat("throughput: ${getMbps(numBytes, Duration.ofNanos(lastPacketTime - firstPacketTime))} mbps")
            addStat("individual module throughput: ${getMbps(numBytes, Duration.ofNanos(totalProcessingDuration))} mbps")
        }
    }

    protected fun next(outPackets: List<PacketInfo>) {
        onExit(outPackets)
        numOutputPackets += outPackets.size
        if (outPackets.isNotEmpty()) {
            nextNode?.processPackets(outPackets)
        }
    }

    /**
     * Allow the implementing class to specify the next handler to invoke with the
     * given packets.  This is necessary for things like [DemuxerNode] which have
     * multiple subsequent paths packets can flow down, so they don't use the singular
     * [nextNode].
     */
    protected fun next(nextNode: Node, outPackets: List<PacketInfo>) {
        onExit(outPackets)
        if (outPackets.isNotEmpty()) {
            numOutputPackets += outPackets.size
            nextNode.processPackets(outPackets)
        }
    }

    private fun onEntry(incomingPackets: List<PacketInfo>) {
        startTime = System.nanoTime()
        if (firstPacketTime == -1L) {
            firstPacketTime = startTime
        }
        incomingPackets.forEach {
            numBytes += it.packet.size
            it.addEvent(nodeEntryString)
        }
        lastPacketTime = System.nanoTime()
        numInputPackets += incomingPackets.size
    }

    private fun onExit(outPackets: List<PacketInfo>) {
        val processingDuration = System.nanoTime() - startTime
        totalProcessingDuration += processingDuration
        outPackets.forEach {
            it.addEvent(nodeExitString)
        }
    }
}

class ConditionalPacketPath {
    var name: String by Delegates.notNull()
    var predicate: PacketPredicate by Delegates.notNull()
    var path: Node by Delegates.notNull()
}

