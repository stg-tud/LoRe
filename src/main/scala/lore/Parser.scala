package lore

import AST._
import cats.parse.{Parser => P, Parser0 => P0, Rfc5234}
import cats.parse.Rfc5234.{alpha, char, digit, sp}
import cats.implicits._
import cats._
import cats.data.NonEmptyList

object Parser:
  // helpers
  val ws: P0[Unit] = sp.rep0.void // whitespace
  val id: P[ID] = (alpha ~ (alpha | digit).rep0).string
  val number: P[TNum] = digit.rep.string.map(i => TNum(Integer.parseInt(i)))
  val argT: P[TArgT] =
    (((id <* sp.? ~ P.char(':')) <* sp.rep0) ~ id).map { // args with type
      (l: ID, r: Type) => TArgT(l, r)
    }

  // basic terms
  val _var: P[TVar] = id.map(TVar(_)) // variables

  // boolean expressions
  val booleanExpr: P[Term] = P.defer(quantifier | implication)

  // primitives
  val tru: P[TBoolean] = P.string("true").as(TTrue)
  val fls: P[TBoolean] = P.string("false").as(TFalse)
  val parens: P[Term] = // parantheses
    (ws.soft ~ P.char('(') ~ ws).with1 *> P
      .defer(implication) <* P.char(')').surroundedBy(ws)
  val boolFactor: P[Term] =
    P.defer(
      tru.backtrack | fls.backtrack | parens
      // | inSet.backtrack  TODO
      // | numComp.backtrack TODO
        | fieldAcc.backtrack
        | functionCall.backtrack
        | _var
    )

  // helper for boolean expressions with two sides
  val boolTpl = (factor: P[Term], seperator: P[Unit]) =>
    factor ~ ((ws.soft ~ seperator.backtrack ~ ws) *> factor).?
  val implication: P[Term] =
    P.defer(boolTpl(equality, P.string("==>"))).map {
      case (left, None)        => left
      case (left, Some(right)) => TImpl(left = left, right = right)
    }
  val equality: P[Term] =
    P.defer(boolTpl(inequality, P.string("==") <* P.char('>').unary_!))
      .map {
        case (left, None)        => left
        case (left, Some(right)) => TEq(left = left, right = right)
      }
  val inequality: P[Term] =
    P.defer(boolTpl(conjunction, P.string("!="))).map {
      case (left, None)        => left
      case (left, Some(right)) => TIneq(left = left, right = right)
    }

  // helper for boolean expressions with arbitrarily long sequences like && and ||
  val boolSeq = (factor: P[Term], seperator: String) =>
    ((ws.soft.with1 *> factor) ~
      (((ws.soft ~ P.string(seperator) ~ ws)).void
        .as(seperator)
        .with1 ~ factor).rep0).map(evalBoolSeq)
  val conjunction: P[Term] =
    P.defer(boolSeq(disjunction, "&&"))
  val disjunction: P[Term] =
    P.defer(boolSeq(boolFactor, "||"))
  def evalBoolSeq(seq: (Term, Seq[(String, Term)])): Term =
    seq match
      case (root, Nil) => root
      case (root, ("||", x) :: xs) =>
        TDisj(left = root, right = evalBoolSeq(x, xs))
      case (root, ("&&", x) :: xs) =>
        TConj(left = root, right = evalBoolSeq(x, xs))
      case sth =>
        throw new IllegalArgumentException(s"Not a boolean expression: $sth")

  // set expressions
  val inSet: P[TBoolean] = P
    .defer((term.surroundedBy(ws) <* P.string("in")) ~ term.surroundedBy(ws))
    .map { (left, right) =>
      TInSet(left, right)
    }

  val numComp: P[Term] = P.string("TODO").as(TTrue) // TODO: number comparison

  // quantifiers
  val quantifierVars: P[NonEmptyList[TArgT]] =
    (argT <* ws).repSep(P.char(',') <* ws)
  val triggers: P0[List[TViper]] = P.unit.as(List[TViper]())
  val forall: P[TForall] =
    (((P.string("forall") ~ ws *> quantifierVars) <* P.string(
      "::"
    ) ~ ws) ~ triggers ~ booleanExpr).map { case ((vars, triggers), body) =>
      TForall(vars = vars, triggers = triggers, body = body)
    }
  val exists: P[TExists] =
    ((P.string("exists") ~ ws *> quantifierVars <* P
      .string("::")
      .surroundedBy(ws)) ~ booleanExpr).map { case (vars, body) =>
      TExists(vars = vars, body = body)
    }
  val quantifier: P[TQuantifier] = forall | exists

  // object orientation
  val args = P.defer0(term.repSep0(P.char(',') ~ ws))
  val objFactor = P.defer(functionCall.backtrack | _var)
  val fieldAcc: P[TFAcc] =
    P.defer(
      objFactor.soft ~
        (P.char('.') *> id ~ (P.char('(') *> args <* (ws ~ P.char(')'))).?).rep
    ).map((parent, rest) => evalFieldAcc(parent, rest.toList))
  def evalFieldAcc(s: (Term, List[(ID, Option[List[Term]])])): TFAcc =
    s match 
      case (parent: TFAcc, Nil) => parent
      case (parent, (field, args) :: Nil) =>
        TFAcc(parent = parent, field = field, args = args.getOrElse(Seq()))
      case (parent, (field, args) :: rest) =>
        evalFieldAcc(
          TFAcc(parent = parent, field = field, args = args.getOrElse(Seq())),
          rest
        )
      case _ =>
        throw new IllegalArgumentException(s"Not a valid field access: $s")
    
  val functionCall: P[TFunC] = P
    .defer(id ~ (P.char('(') *> args) <* P.char(')'))
    .map { (id, arg) =>
      TFunC(name = id, args = arg)
    }

  // programs are sequences of terms
  val term: P[Term] =
    P.defer(
      fieldAcc.backtrack | functionCall.backtrack | booleanExpr.backtrack | number | _var
    )
  val prog: P[NonEmptyList[Term]] = term.repSep(ws).between(ws, P.end)
