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

import java.util.UUID

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

import io.netty.buffer.ByteBuf
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.network.PacketBuffer
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.ITextComponent
import shapeless._

/**
  * A discriminator typeclass used for serializing sealed traits.
  * @param byte The byte to use for this case.
  */
@implicitNotFound("A discriminator is needed to create a message converter for ${A}")
case class Discriminator[A](byte: Byte)
object Discriminator {
  def apply[A](implicit discriminator: Discriminator[A]): Discriminator[A] = discriminator
}

/**
  * A typeclass that can convert a type to and from bytes, for use in sending packets.
  * @tparam A The type to convert.
  */
@implicitNotFound("Don't know how to convert ${A} to and from bytes")
trait MessageConverter[A] { self =>

  /**
    * Write an instance to a byte buffer.
    */
  def writeBytes(a: A, buf: ByteBuf): Unit

  /**
    * Read an instance from a byte buffer.
    */
  def readBytes(buf: ByteBuf): A

  /**
    * Modify this message converter.
    */
  def modify[B](from: A => B)(to: B => A): MessageConverter[B] = new MessageConverter[B] {
    override def writeBytes(a: B, buf: ByteBuf): Unit = self.writeBytes(to(a), buf)

    override def readBytes(buf: ByteBuf): B = from(self.readBytes(buf))
  }
}
object MessageConverter {

  def apply[A](implicit converter: MessageConverter[A]): MessageConverter[A] = converter

  def create[A](read: ByteBuf => A)(write: (ByteBuf, A) => Unit): MessageConverter[A] = new MessageConverter[A] {
    override def writeBytes(a: A, buf: ByteBuf): Unit = write(buf, a)
    override def readBytes(buf: ByteBuf): A           = read(buf)
  }

  def createExtra[A](from: PacketBuffer => A)(to: (PacketBuffer, A) => Unit): MessageConverter[A] =
    create(buf => from(new PacketBuffer(buf)))((buf, a) => to(new PacketBuffer(buf), a))

  def write[A](a: A, buf: ByteBuf)(implicit converter: MessageConverter[A]): Unit = converter.writeBytes(a, buf)
  def read[A](buf: ByteBuf)(implicit converter: MessageConverter[A]): A           = converter.readBytes(buf)

  implicit val boolConverter: MessageConverter[Boolean]  = create(_.readBoolean())(_.writeBoolean(_))
  implicit val byteConverter: MessageConverter[Byte]     = create(_.readByte())(_.writeByte(_))
  implicit val shortConverter: MessageConverter[Short]   = create(_.readShort())(_.writeShort(_))
  implicit val intConverter: MessageConverter[Int]       = create(_.readInt())(_.writeInt(_))
  implicit val longConverter: MessageConverter[Long]     = create(_.readLong())(_.writeLong(_))
  implicit val floatConverter: MessageConverter[Float]   = create(_.readFloat())(_.writeFloat(_))
  implicit val doubleConverter: MessageConverter[Double] = create(_.readDouble())(_.writeDouble(_))
  implicit val charConverter: MessageConverter[Char]     = create(_.readChar())(_.writeChar(_))

  implicit val stringConverter: MessageConverter[String]     = createExtra(_.readString(32767))(_.writeString(_))
  implicit val blockPosConverter: MessageConverter[BlockPos] = createExtra(_.readBlockPos())(_.writeBlockPos(_))
  implicit val textConverter: MessageConverter[ITextComponent] =
    createExtra(_.readTextComponent())(_.writeTextComponent(_))
  implicit val uuidConverter: MessageConverter[UUID]          = createExtra(_.readUniqueId())(_.writeUniqueId(_))
  implicit val tagConverter: MessageConverter[NBTTagCompound] = createExtra(_.readCompoundTag())(_.writeCompoundTag(_))
  implicit val stackConverter: MessageConverter[ItemStack]    = createExtra(_.readItemStack())(_.writeItemStack(_))
  implicit val resourceLocationConverter: MessageConverter[ResourceLocation] =
    createExtra(_.readResourceLocation())(_.writeResourceLocation(_))

  implicit def enumConverter[A <: Enum[A]](implicit classTag: ClassTag[A]): MessageConverter[A] =
    createExtra(_.readEnumValue(classTag.runtimeClass.asInstanceOf[Class[A]]))(_.writeEnumValue(_))

  implicit def seqConverter[A: MessageConverter]: MessageConverter[Seq[A]] = new MessageConverter[Seq[A]] {

    override def writeBytes(a: Seq[A], buf: ByteBuf): Unit = {
      buf.write(a.size)
      for (obj <- a) {
        buf.write(obj)
      }
    }

    override def readBytes(buf: ByteBuf): Seq[A] = Seq.fill(buf.read[Int])(buf.read[A])
  }

  implicit def optionConverter[A: MessageConverter]: MessageConverter[Option[A]] = new MessageConverter[Option[A]] {

    override def writeBytes(a: Option[A], buf: ByteBuf): Unit = {
      buf.write(a.isDefined)
      a.foreach(buf.write[A])
    }

    override def readBytes(buf: ByteBuf): Option[A] =
      if (buf.read[Boolean]) Some(buf.read[A])
      else None
  }

  class Deriver[A] {
    def apply[Repr](
        implicit generic: Generic.Aux[A, Repr],
        converter: Lazy[MessageConverter[Repr]]
    ): MessageConverter[A] = caseConverter[A, generic.Repr]
  }
  def mkDeriver[A]: Deriver[A] = new Deriver[A]

  implicit val hNilConverter: MessageConverter[HNil] = new MessageConverter[HNil] {
    override def writeBytes(a: HNil, buf: ByteBuf): Unit = ()
    override def readBytes(buf: ByteBuf): HNil           = HNil
  }

  implicit def hConsConverter[H, T <: HList](
      implicit hConverter: MessageConverter[H],
      tConverter: Lazy[MessageConverter[T]]
  ): MessageConverter[H :: T] = new MessageConverter[H :: T] {
    override def writeBytes(a: H :: T, buf: ByteBuf): Unit = {
      hConverter.writeBytes(a.head, buf)
      tConverter.value.writeBytes(a.tail, buf)
    }

    override def readBytes(buf: ByteBuf): H :: T = {
      val h = hConverter.readBytes(buf)
      val t = tConverter.value.readBytes(buf)
      h :: t
    }
  }

  implicit val cNilConverter: MessageConverter[CNil] = new MessageConverter[CNil] {

    override def writeBytes(a: CNil, buf: ByteBuf): Unit = a.impossible

    override def readBytes(buf: ByteBuf): CNil = sys.error("CNip")
  }

  implicit def coProdConverter[H, T <: Coproduct](
      implicit hConverter: MessageConverter[H],
      discriminator: Discriminator[H],
      tConverter: Lazy[MessageConverter[T]]
  ): MessageConverter[H :+: T] = new MessageConverter[H :+: T] {

    override def writeBytes(a: H :+: T, buf: ByteBuf): Unit = a match {
      case Inl(head) =>
        buf.writeByte(discriminator.byte)
        hConverter.writeBytes(head, buf)
      case Inr(tail) => tConverter.value.writeBytes(tail, buf)
    }

    override def readBytes(buf: ByteBuf): H :+: T = {
      buf.markReaderIndex()
      if (discriminator.byte == buf.readByte()) {
        Inl(hConverter.readBytes(buf))
      } else {
        buf.resetReaderIndex()
        Inr(tConverter.value.readBytes(buf))
      }
    }
  }

  implicit def caseConverter[A, Repr](
      implicit gen: Generic.Aux[A, Repr],
      converter: Lazy[MessageConverter[Repr]]
  ): MessageConverter[A] = new MessageConverter[A] {
    override def writeBytes(a: A, buf: ByteBuf): Unit = converter.value.writeBytes(gen.to(a), buf)
    override def readBytes(buf: ByteBuf): A           = gen.from(converter.value.readBytes(buf))
  }

  implicit class Ops(val buffer: ByteBuf) extends AnyVal {
    def write[A: MessageConverter](obj: A): Unit = MessageConverter.write[A](obj, buffer)
    def read[A: MessageConverter]: A             = MessageConverter.read[A](buffer)
  }
}
