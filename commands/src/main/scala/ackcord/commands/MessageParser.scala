/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ackcord.commands

import ackcord.CacheSnapshot
import ackcord.commands.MessageParser.RemainingAsString
import ackcord.data._
import akka.NotUsed
import cats.data.{EitherT, OptionT, StateT}
import cats.mtl.syntax.all._
import cats.mtl.{ApplicativeHandle, MonadState}
import cats.syntax.all._
import cats.{Monad, MonadError}

import scala.language.{higherKinds, implicitConversions}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

/**
  * MessageParser is a typeclass to simplify parsing messages. It can derive
  * instances for any ADT, and makes it much easier to work with messages.
  * @tparam A The type to parse.
  */
trait MessageParser[A] { self =>

  /**
    * A program to parse a message into the needed types.
    */
  def parse[F[_]](
      implicit c: CacheSnapshot[F],
      F: Monad[F],
      E: ApplicativeHandle[F, String],
      S: MonadState[F, List[String]]
  ): F[A]

  /**
    * Create a new parser by filtering the values created by this parser.
    * @param f The predicate.
    * @param error The error message if the value does not match the predicate.
    */
  def filterWithError(f: A => Boolean, error: String): MessageParser[A] = new MessageParser[A] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[A] = self.parse[F].flatMap(res => if (f(res)) res.pure else error.raise)
  }

  /**
    * Apply a partial function of this parser, returning the error if the
    * function isn't defined.
    * @param error The error to return if the partial function isn't defined.
    * @param pf The partial function to apply.
    * @tparam B The new parser type.
    */
  def collectWithError[B](error: String)(pf: PartialFunction[A, B]): MessageParser[B] = new MessageParser[B] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[B] =
      self.parse.flatMap {
        case obj if pf.isDefinedAt(obj) => pf(obj).pure
        case _                          => error.raise
      }
  }
}
object MessageParser extends MessageParserInstances with DeriveMessageParser {

  //Just something here to get a different implicit
  case class RemainingAsString(remaining: String)
  implicit def remaining2String(remaining: RemainingAsString): String = remaining.remaining

  def apply[A](implicit parser: MessageParser[A]): MessageParser[A] = parser

  implicit val messageParserMonad: MonadError[MessageParser, String] = new MonadError[MessageParser, String] {
    override def pure[A](x: A): MessageParser[A] = new MessageParser[A] {
      override def parse[F[_]](
          implicit c: CacheSnapshot[F],
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[A] = F.pure(x)
    }

    override def flatMap[A, B](fa: MessageParser[A])(f: A => MessageParser[B]): MessageParser[B] =
      new MessageParser[B] {
        override def parse[F[_]](
            implicit c: CacheSnapshot[F],
            F: Monad[F],
            E: ApplicativeHandle[F, String],
            S: MonadState[F, List[String]]
        ): F[B] = fa.parse.flatMap(f(_).parse)
      }

    override def tailRecM[A, B](a: A)(f: A => MessageParser[Either[A, B]]): MessageParser[B] = new MessageParser[B] {
      override def parse[F[_]](
          implicit c: CacheSnapshot[F],
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[B] = F.tailRecM(a)(f(_).parse)
    }

    override def raiseError[A](e: String): MessageParser[A] = new MessageParser[A] {
      override def parse[F[_]](
          implicit c: CacheSnapshot[F],
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[A] = e.raise
    }

    override def handleErrorWith[A](fa: MessageParser[A])(f: String => MessageParser[A]): MessageParser[A] =
      new MessageParser[A] {
        override def parse[F[_]](
            implicit c: CacheSnapshot[F],
            F: Monad[F],
            E: ApplicativeHandle[F, String],
            S: MonadState[F, List[String]]
        ): F[A] = fa.parse.handleWith[String](f(_).parse)
      }
  }

  /**
    * Parse a message as an type
    * @param args The message to parse
    * @param parser The parser to use
    * @param c The cache to use
    * @tparam A The type to parse the message as
    * @return Left with an error message if it failed to parse, or Right with the parsed type
    */
  def parseResultEitherT[F[_], A](
      args: List[String],
      parser: MessageParser[A]
  )(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, A] = parseEitherT(args, parser).map(_._2)

  def parseEitherT[F[_], A](
      args: List[String],
      parser: MessageParser[A]
  )(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, (List[String], A)] = {
    import cats.mtl.instances.handle._
    import cats.mtl.instances.state._
    import cats.mtl.instances.statet._

    implicit val cMapped: CacheSnapshot[StateT[EitherT[F, String, ?], List[String], ?]] =
      c.mapK(EitherT.liftK[F, String].andThen(StateT.liftK[EitherT[F, String, ?], List[String]]))

    parser.parse[StateT[EitherT[F, String, ?], List[String], ?]].run(args)
  }

}

trait MessageParserInstances {

  private def eitherToF[F[_], A](either: Either[String, A])(implicit E: ApplicativeHandle[F, String]): F[A] =
    either.fold(_.raise, E.applicative.pure)

  /**
    * Create a parser from a string
    * @param f The function to transform the string with
    * @tparam A The type to parse
    */
  def fromString[A](f: String => A): MessageParser[A] = new MessageParser[A] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[A] = S.get.flatMap {
      case Nil          => E.raise("No more arguments left")
      case head :: tail => S.set(tail).as(f(head))
    }
  }

  /**
    * Parse a string with a function that can throw
    * @param f The function to transform the string with. This can throw an exception
    * @tparam A The type to parse
    */
  def withTry[A](f: String => A): MessageParser[A] = fromTry(s => Try(f(s)))

  /**
    * Parse a string with a try
    * @param f The function to transform the string with.
    * @tparam A The type to parse
    */
  def fromTry[A](f: String => Try[A]): MessageParser[A] = new MessageParser[A] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[A] = S.get.flatMap {
      case Nil => E.raise("No more arguments left")
      case head :: tail =>
        S.set(tail).as(f(head)).flatMap {
          case Success(value) => value.pure
          case Failure(e)     => e.getMessage.raise
        }
    }
  }

  /**
    * Same as [[fromTry]] but with a custom error.
    * @param errorMessage The error message to use
    * @param f The function to transform the string with.
    * @tparam A The type to parse
    */
  def fromTryCustomError[A](errorMessage: String)(f: String => Try[A]): MessageParser[A] = fromTry(f).adaptError {
    case _ => errorMessage
  }

  implicit val remainingStringParser: MessageParser[MessageParser.RemainingAsString] =
    new MessageParser[RemainingAsString] {
      override def parse[F[_]](
          implicit c: CacheSnapshot[F],
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[RemainingAsString] = S.get.map(s => RemainingAsString(s.mkString(" "))) <* S.set(Nil)
    }

  implicit val stringParser: MessageParser[String]   = fromString(identity)
  implicit val byteParser: MessageParser[Byte]       = withTry(_.toByte)
  implicit val shortParser: MessageParser[Short]     = withTry(_.toShort)
  implicit val intParser: MessageParser[Int]         = withTry(_.toInt)
  implicit val longParser: MessageParser[Long]       = withTry(_.toLong)
  implicit val floatParser: MessageParser[Float]     = withTry(_.toFloat)
  implicit val doubleParser: MessageParser[Double]   = withTry(_.toDouble)
  implicit val booleanParser: MessageParser[Boolean] = withTry(_.toBoolean)

  val userRegex: Regex    = """<@!?(\d+)>""".r
  val channelRegex: Regex = """<#(\d+)>""".r
  val roleRegex: Regex    = """<@&(\d+)>""".r
  val emojiRegex: Regex   = """<:\w+:(\d+)>""".r

  trait HighFunc[F[_[_]], G[_[_]]] {
    def apply[A[_]](fa: F[A]): G[A]
  }

  private def snowflakeParser[C](
      name: String,
      regex: Regex,
      getObj: SnowflakeType[C] => HighFunc[CacheSnapshot, OptionT[?[_], C]]
  ): MessageParser[C] = new MessageParser[C] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[C] = S.get.flatMap {
      case Nil => "No more arguments left".raise
      case head :: tail =>
        val optMatch = regex
          .findFirstMatchIn(head)
          .filter(m => m.start == 0 && m.end == head.length)
          .toRight(s"Invalid $name specified")

        val res = eitherToF(optMatch)
          .flatMap { m =>
            val snowflake = SnowflakeType[C](RawSnowflake(m.group(1)))
            getObj(snowflake)(c).toRight(s"${name.capitalize} not found").value
          }
          .flatMap(e => eitherToF(e))

        res <* S.set(tail)
    }
  }

  implicit val userParser: MessageParser[User] =
    snowflakeParser(
      "user",
      userRegex,
      id =>
        new HighFunc[CacheSnapshot, OptionT[?[_], User]] {
          override def apply[A[_]](fa: CacheSnapshot[A]): OptionT[A, User] = fa.getUser(id)
      }
    )
  implicit val channelParser: MessageParser[Channel] =
    snowflakeParser(
      "channel",
      channelRegex,
      id =>
        new HighFunc[CacheSnapshot, OptionT[?[_], Channel]] {
          override def apply[A[_]](fa: CacheSnapshot[A]): OptionT[A, Channel] = fa.getChannel(id)
      }
    )
  implicit val roleParser: MessageParser[Role] =
    snowflakeParser(
      "role",
      roleRegex,
      id =>
        new HighFunc[CacheSnapshot, OptionT[?[_], Role]] {
          override def apply[A[_]](fa: CacheSnapshot[A]): OptionT[A, Role] = fa.getRole(id)
      }
    )
  implicit val emojiParser: MessageParser[Emoji] =
    snowflakeParser(
      "emoji",
      emojiRegex,
      id =>
        new HighFunc[CacheSnapshot, OptionT[?[_], Emoji]] {
          override def apply[A[_]](fa: CacheSnapshot[A]): OptionT[A, Emoji] = fa.getEmoji(id)
      }
    )

  implicit val tChannelParser: MessageParser[TChannel] =
    channelParser.collectWithError("Passed in channel is not a text channel") {
      case channel: TChannel => channel
    }

  implicit val guildChannelParser: MessageParser[GuildChannel] =
    channelParser.collectWithError("Passed in channel is not a guild channel") {
      case channel: GuildChannel => channel
    }

  implicit val tGuildChannelParser: MessageParser[TGuildChannel] =
    channelParser.collectWithError("Passed in channel is not a guild text channel") {
      case channel: TGuildChannel => channel
    }

  /**
    * A parser that will return all the strings passed to it.
    */
  val allStringsParser: MessageParser[List[String]] = new MessageParser[List[String]] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[List[String]] = S.get <* S.set(Nil)
  }

  /**
    * A parser that will only succeed if there are no strings left.
    */
  implicit val notUsedParser: MessageParser[NotUsed] = new MessageParser[NotUsed] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[NotUsed] =
      S.inspect(_.isEmpty)
        .ifM(
          F.pure(NotUsed),
          S.get.flatMap(args => E.raise(s"Found dangling arguments: ${args.mkString(", ")}"))
        )
  }

  implicit def optionParser[A](implicit parser: MessageParser[A]): MessageParser[Option[A]] =
    new MessageParser[Option[A]] {
      override def parse[F[_]](
          implicit c: CacheSnapshot[F],
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[Option[A]] =
        parser.parse.map[Option[A]](Some.apply).handle[String](_ => None)

    }
}

trait DeriveMessageParser {
  import shapeless._
  implicit val hNilParser: MessageParser[HNil] = Monad[MessageParser].pure(HNil)

  implicit def hListParser[Head, Tail <: HList](
      implicit
      headParser: Lazy[MessageParser[Head]],
      tailParser: Lazy[MessageParser[Tail]]
  ): MessageParser[Head :: Tail] = new MessageParser[Head :: Tail] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[Head :: Tail] =
      for {
        h <- headParser.value.parse
        t <- tailParser.value.parse
      } yield h :: t

  }

  implicit val cNilParser: MessageParser[CNil] = new MessageParser[CNil] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[CNil] = throw new IllegalStateException("Tried to parse CNil")
  }

  implicit def coProductParser[Head, Tail <: Coproduct](
      implicit
      headParser: Lazy[MessageParser[Head]],
      tailParser: Lazy[MessageParser[Tail]]
  ): MessageParser[Head :+: Tail] = new MessageParser[Head :+: Tail] {
    override def parse[F[_]](
        implicit c: CacheSnapshot[F],
        F: Monad[F],
        E: ApplicativeHandle[F, String],
        S: MonadState[F, List[String]]
    ): F[Head :+: Tail] = {
      val head2 = headParser.value.parse.map[Head :+: Tail](Inl.apply)
      val tail2 = tailParser.value.parse.map[Head :+: Tail](Inr.apply)

      head2.handleWith[String](_ => tail2)
    }
  }

  object Auto {
    implicit def deriveParser[A, Repr](
        implicit gen: Generic.Aux[A, Repr],
        ser: Lazy[MessageParser[Repr]]
    ): MessageParser[A] = ser.value.map(gen.from)
  }
}