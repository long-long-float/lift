package exploration

import ir._
import ir.ast._

object Rewrite {

  private def listAllPossibleRewritesForAllRules(lambda: Lambda): Seq[(Rule, Expr)] = {
    Rules.rules.map(rule => listAllPossibleRewrites(lambda, rule)).reduce(_ ++ _)
  }

  private def listAllPossibleRewrites(lambda: Lambda,
                                      rule: Rule): Seq[(Rule, Expr)] = {
    Context.updateContext(lambda.body, new Context)

    Expr.visitWithState(Seq[(Rule, Expr)]())( lambda.body, (e, s) => {
      if (rule.rewrite.isDefinedAt(e) && rule.isValid(e.context)) {
        s :+ (rule, e)
      } else s
    })
  }

  private def applyRuleAt(lambda: Lambda, ruleAt: (Rule, Expr)): Lambda = {
    val rule = ruleAt._1
    val oldE = ruleAt._2
    // same as FunDecl.replace( ... )
    Lambda(lambda.params, Expr.replace(lambda.body, oldE, rule.rewrite(oldE)))
  }

  def rewrite(lambda: Lambda, levels: Int = 1): Seq[Lambda] = {
    TypeChecker.check(lambda.body)

    val allRulesAt = listAllPossibleRewritesForAllRules(lambda)
    val rewritten = allRulesAt.map(ruleAt => applyRuleAt(lambda, ruleAt))

    val (g, notG) = rewritten.partition( _.isGenerable )

    if (levels == 1) {
      g
    } else {
      g ++ notG.flatMap( l => rewrite(l, levels-1))
    }
  }
}
