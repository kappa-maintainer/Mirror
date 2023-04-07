/*
 * This file is part of Mirror, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018 TeamNightclipse
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package net.katsstuff.mirror.network.scalachannel

import java.lang.reflect.Method

import scala.reflect.ClassTag

import io.netty.channel.{ChannelFutureListener, ChannelHandler, ChannelPipeline}
import net.katsstuff.mirror.misc.IdState
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.{INetHandler, Packet}
import net.minecraftforge.fml.common.network.{FMLOutboundHandler, NetworkRegistry}
import net.minecraftforge.fml.relauncher.Side

/**
  * A copy of the [[net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper]]
  * to instead be more easily used from Scala. The system is typeclass based,
  * and any type can be a packet, provided it can be serialized, and has a handler.
  * @param channelName The name of the channel.
  */
class ScalaNetworkWrapper(channelName: String) {

  private val codec    = new ScalaIndexedMessageChannel
  private val channels = NetworkRegistry.INSTANCE.newChannel(channelName, codec)

  private val defaultChannelPipeline: Class[_] = Class.forName("io.netty.channel.DefaultChannelPipeline")
  private val generateNameMethod: Method = {
    val m = defaultChannelPipeline.getDeclaredMethod("generateName", classOf[ChannelHandler])
    m.setAccessible(true)
    m
  }

  protected def registerMessages(state: IdState[Unit]): Unit = state.run(0)

  protected def registerMessage[A: ClassTag: MessageConverter](implicit handler: MessageHandler[A, _]): IdState[Unit] =
    IdState(i => (i + 1, registerMessage(handler, i.toByte, handler.side)))

  protected def registerMessage[A: ClassTag, Reply](handler: MessageHandler[A, Reply], discriminator: Byte, side: Side)(
      implicit converter: MessageConverter[A]
  ): Unit = {
    codec.addDiscriminator(discriminator, converter)
    val channel = channels.get(side)

    val tpe            = channel.findChannelHandlerNameForType(classOf[ScalaIndexedMessageChannel])
    val handlerWrapper = new SimpleChannelHandlerScalaWrapper(handler, side)

    channel.pipeline.addAfter(tpe, generateName(channel.pipeline, handlerWrapper), handlerWrapper)
  }

  private def generateName(pipeline: ChannelPipeline, handler: ChannelHandler) =
    generateNameMethod.invoke(defaultChannelPipeline.cast(pipeline), handler).asInstanceOf[String]

  /**
    * Construct a minecraft packet from the supplied message. Can be used where minecraft packets are required, such as
    * [[net.minecraft.tileentity.TileEntity#getDescriptionPacket]]
    *
    * @param message The message to translate into packet form
    * @return A minecraft [[Packet]] suitable for use in minecraft APIs
    */
  def getPacketFrom[A](message: A): Packet[_ <: INetHandler] = channels.get(Side.SERVER).generatePacketFrom(message)

  /**
    * Send this message to everyone.
    * The [[MessageHandler]] for this message type should be on the CLIENT side.
    *
    * @param message The message to send
    */
  def sendToAll[A: HasClientHandler](message: A) {
    channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.ALL)
    channels.get(Side.SERVER).writeAndFlush(message).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
  }

  /**
    * Send this message to the specified player.
    * The [[MessageHandler]] for this message type should be on the CLIENT side.
    *
    * @param message The message to send
    * @param player The player to send it to
    */
  def sendTo[A: HasClientHandler](message: A, player: EntityPlayerMP) {
    channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.PLAYER)
    channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(player)
    channels.get(Side.SERVER).writeAndFlush(message).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
  }

  /**
    * Send this message to everyone within a certain range of a point.
    * The [[MessageHandler]] for this message type should be on the CLIENT side.
    *
    * @param message The message to send
    * @param point The { @link TargetPoint} around which to send
    */
  def sendToAllAround[A: HasClientHandler](message: A, point: TargetPoint) {
    channels
      .get(Side.SERVER)
      .attr(FMLOutboundHandler.FML_MESSAGETARGET)
      .set(FMLOutboundHandler.OutboundTarget.ALLAROUNDPOINT)
    channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(point.toForge)
    channels.get(Side.SERVER).writeAndFlush(message).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
  }

  /**
    * Send this message to everyone within the supplied dimension.
    * The [[MessageHandler]] for this message type should be on the CLIENT side.
    *
    * @param message The message to send
    * @param dimensionId The dimension id to target
    */
  def sendToDimension[A: HasClientHandler](message: A, dimensionId: Int) {
    channels
      .get(Side.SERVER)
      .attr(FMLOutboundHandler.FML_MESSAGETARGET)
      .set(FMLOutboundHandler.OutboundTarget.DIMENSION)
    channels.get(Side.SERVER).attr(FMLOutboundHandler.FML_MESSAGETARGETARGS).set(Int.box(dimensionId))
    channels.get(Side.SERVER).writeAndFlush(message).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
  }

  /**
    * Send this message to the server.
    * The [[MessageHandler]] for this message type should be on the SERVER side.
    *
    * @param message The message to send
    */
  def sendToServer[A: HasServerHandler](message: A) {
    channels.get(Side.CLIENT).attr(FMLOutboundHandler.FML_MESSAGETARGET).set(FMLOutboundHandler.OutboundTarget.TOSERVER)
    channels.get(Side.CLIENT).writeAndFlush(message).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
  }
}
