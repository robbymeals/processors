package org.clulab.processors.bionlp

import java.util.regex.Pattern

import edu.stanford.nlp.ling.CoreLabel
import BioNLPTokenizerPostProcessor._
import edu.stanford.nlp.process.CoreLabelTokenFactory
import org.clulab.struct.MutableNumber

import scala.collection.mutable.ArrayBuffer

/**
 * Post processes CoreNLP tokenization so it suits bio analysis
 * User: mihais
 * Date: 11/16/14
 */
class BioNLPTokenizerPostProcessor(val unslashableTokens:Set[String]) {

  def process(input:Array[CoreLabel]):Array[CoreLabel] = {
    var tokens = input

    // revert CoreNLP's tokenization that is too aggressive
    tokens = revertAggressiveTokenization(tokens)

    // "non" is a special prefix, because it drives negation detection
    tokens = breakOnPattern(tokens, Pattern.compile("(non)(-)(\\w+)", Pattern.CASE_INSENSITIVE))

    // tokenize around "-" when the suffix is a known verb, noun, or other important word
    tokens = breakOnPattern(tokens, dashSuffixes)

    // tokenize around "-" when the prefix is a known site, such as "Tyr" in "Tyr-phosphorylated"
    tokens = breakOnPattern(tokens, sitePrefixes)

    // break n-ary complexes
    tokens = breakNaryComplex(tokens)

    // break mutations
    // TODO: this needs improvement, see Dane's comments
    tokens = breakMutant(tokens, SINGLEDASH_PATTERN)

    // break all (well, most) tokens containing a single slash; try to replace them with an enumeration
    // does not apply to protein family names, which often contain slashes
    tokens = breakOneSlash(tokens, SINGLESLASH_PATTERN)

    // re-join trailing or preceding - or + to previous digit
    tokens = joinSigns(tokens)

    tokens
  }

  def isSpecialToken(s:String) = unslashableTokens.contains(s.toLowerCase)

  def breakOnPattern(tokens:Array[CoreLabel], pattern:Pattern):Array[CoreLabel] = {
    val output = new ArrayBuffer[CoreLabel]
    for(i <- tokens.indices) {
      val token = tokens(i)
      val matcher = pattern.matcher(token.word())
      if (matcher.matches()) {
        val sepPos = matcher.start(2)
        val s1 = token.word().substring(0, sepPos)
        if(COMMON_PREFIXES.contains(s1.toLowerCase)) {
          // do not separate here; these prefixes cannot live on their own
          output += token
        } else {
          if (!DISCARD_STANDALONE_DASHES || !s1.equals("-")) {
            output += tokenFactory.makeToken(s1, token.beginPosition(), sepPos)
          }
          val sep = matcher.group(2)
          if (!DISCARD_STANDALONE_DASHES || !sep.equals("-")) {
            output += tokenFactory.makeToken(sep, token.beginPosition() + sepPos, 1)
          }
          val s3 = token.word().substring(sepPos + 1)
          if (!DISCARD_STANDALONE_DASHES || !s3.equals("-")) {
            output += tokenFactory.makeToken(s3, token.beginPosition() + sepPos + 1,
              token.endPosition() - token.beginPosition() - sepPos - 1)
          }
        }
      } else {
        output += token
      }
    }
    output.toArray
  }

  def breakOneSlash(tokens:Array[CoreLabel], pattern:Pattern):Array[CoreLabel] = {
    val output = new ArrayBuffer[CoreLabel]

    for(i <- tokens.indices) {
      val token = tokens(i)
      val matcher = pattern.matcher(token.word())
      if (matcher.matches() &&
          ! isMeasurementUnit(token.word()) && // skip measurement units such as "ng/ml"
          ! isSpecialToken(token.word())) { // skip special tokens such as family names containing slash such as "MEK1/MEK2"
        val sepPos = matcher.start(2)
        val s1 = token.word().substring(0, sepPos)
        val s3 = token.word().substring(sepPos + 1)

        output += tokenFactory.makeToken(s1, token.beginPosition(), sepPos)
        output += tokenFactory.makeToken("and", token.beginPosition() + sepPos, 1) // replace "/" with "and"; it parses better
        output += tokenFactory.makeToken(s3, token.beginPosition() + sepPos + 1,
          token.endPosition() - token.beginPosition() - sepPos - 1)
      } else {
        output += token
      }
    }

    output.toArray
  }

  /** True if this a Site or some Mutation */
  def isModification(s:String):Boolean = {
    for(p <- MODIFICATIONS) {
      val m = p.matcher(s)
      if(m.matches()) return true
    }
    false
  }

  class ComplexSubToken(var text:String, var start:Int, var length:Int) {
    override def toString:String = s"[$text, $start, $length]"
  }

  def breakNaryComplex(tokens:Array[CoreLabel]):Array[CoreLabel] = {
    //print("TOKENIZING SENTENCE:")
    //for(token <- tokens) print(" [" + token.word() + "]")
    //println()

    val output = new ArrayBuffer[CoreLabel]
    for(i <- tokens.indices) {
      val token = tokens(i)
      val sepMatcher = VALID_COMPLEX_SEPARATOR_PATTERN.matcher(token.word())
      if (sepMatcher.find() && // contains a dash or some known separator
          ((i < tokens.length - 1 && isComplex(tokens(i + 1).word())) || // followed by "complex", or
           (i > 0 && isComplex(tokens(i - 1).word())))){ // preceded by "complex"

        // start breaking down the token into sub-tokens based on separators such as "-" or "/"
        var tokenStart = 0 // where the current sub-token starts
        sepMatcher.reset()
        val subTokens = new ArrayBuffer[ComplexSubToken]()

        // find all valid sub-tokens inside this word, e.g., "Mek/Ras/Akt1" is broken into "Mek", "Ras", and "Akt1"
        // invalid protein names (e.g., the "2" suffix in "Mek-Smad-2) are appended to the previous token ("Smad")
        while(sepMatcher.find(tokenStart)) {
          val sepPos = sepMatcher.start()
          appendSubToken(subTokens, token, tokenStart, sepPos)
          tokenStart = sepMatcher.end()
        }
        // left over at the end of the word
        if(tokenStart < token.word().length) {
          appendSubToken(subTokens, token, tokenStart, token.word().length)
        }

        //println("SUBTOKENS: " + subTokens.mkString(", "))

        // now create actual tokens from all these sub-tokens
        for(i <- subTokens.indices) {
          // add a "," or "and" before each non-start token
          if(i > 0) {
            val prev = subTokens(i - 1)
            if(i < subTokens.length - 1) {
              output += tokenFactory.makeToken(",", prev.start + prev.length, 1)
            } else {
              if(subTokens.length > 2)
                output += tokenFactory.makeToken(",", prev.start + prev.length, 1) // Oxford commas parse better!
              output += tokenFactory.makeToken("and", prev.start + prev.length, 1)
            }
          }
          // add the actual token
          val crt = subTokens(i)
          output += tokenFactory.makeToken(crt.text, crt.start, crt.length)
        }

      } else {
        output += token
      }
    }
    output.toArray
  }

  private def appendSubToken(
    subTokens:ArrayBuffer[ComplexSubToken],
    token:CoreLabel,
    tokenStart:Int,
    sepPos:Int): Unit = {

    val word = token.word()
    val subToken = word.substring(tokenStart, sepPos)

    if(isValidProtein(subToken) || tokenStart == 0) { // found a valid protein name
    val t = new ComplexSubToken(subToken, token.beginPosition() + tokenStart, sepPos - tokenStart)
      subTokens += t
    } else { // found a likely suffix; append it to the previous token
      val lastToken = subTokens.last
      val prevSep = word(tokenStart - 1) // the separator before the suffix
      lastToken.text = lastToken.text + prevSep + subToken // the previous token extended with the suffix
      lastToken.length += 1 + subToken.length
    }
  }

  def breakMutant(tokens:Array[CoreLabel], pattern:Pattern):Array[CoreLabel] = {
    val output = new ArrayBuffer[CoreLabel]
    for(i <- tokens.indices) {
      val token = tokens(i)
      val matcher = pattern.matcher(token.word())
      if (matcher.matches() && // contains a dash or some known separator
        ((i < tokens.length - 1 && isMutant(tokens(i + 1).word())) || // followed by "mutant", or
         (i > 0 && isMutant(tokens(i - 1).word())))){ // preceded by mutant
        val sepPos = matcher.start(2)
        val s1 = token.word().substring(0, sepPos)
        output += tokenFactory.makeToken(s1, token.beginPosition(), sepPos)
        // "-" is simply removed for mutation modifications
        val s3 = token.word().substring(sepPos + 1)
        output += tokenFactory.makeToken(s3, token.beginPosition() + sepPos + 1,
          token.endPosition() - token.beginPosition() - sepPos - 1)
      } else {
        output += token
      }
    }
    output.toArray
  }

  /**
    * Scans the text left-to-right and reattaches tokens that were tokenized too aggresively by CoreNLP
    */
  def revertAggressiveTokenization(tokens:Array[CoreLabel]):Array[CoreLabel] = {
    //print("SENTENCE BEFORE REVERT:")
    //for(token <- tokens) print(" [" + token.word() + "]")
    //println()

    val output = new ArrayBuffer[CoreLabel]
    var crtToken = new StringBuilder
    var crtTokenBeginPosition = 0
    var i = 0
    while (i < tokens.length) {
      val howManyConnectingTokens = new MutableNumber[Int](0)
      if(i > 0 && i < tokens.length - 1 &&
         countConnectingTokens(tokens, i, howManyConnectingTokens)) {
        // found an aggressive tokenization for this sequence of tokens; revert it
        for(j <- 0 to howManyConnectingTokens.value) {
          crtToken.append(tokens(i + j).word())
        }
        i += howManyConnectingTokens.value + 1
      } else {
        if(crtToken.nonEmpty) {
          val word = crtToken.toString()
          output += tokenFactory.makeToken(word, crtTokenBeginPosition, word.length)
          crtToken = new StringBuilder
        }
        crtToken.append(tokens(i).word())
        crtTokenBeginPosition = tokens(i).beginPosition
        i += 1
      }
    }
    if(crtToken.nonEmpty) {
      val word = crtToken.toString()
      output += tokenFactory.makeToken(word, crtTokenBeginPosition, word.length)
    }
    output.toArray
  }

  def joinSigns(tokens:Array[CoreLabel]):Array[CoreLabel] = {
    val output = new ArrayBuffer[CoreLabel]
    var i = 0
    while(i < tokens.length) {
      // -/-
      if(i < tokens.length - 3 &&
        tokens(i).endPosition == tokens(i + 1).beginPosition &&
        tokens(i + 1).word == "-" &&
        tokens(i + 2).word == "/" &&
        tokens(i + 3).word == "-"){
        val word = tokens(i).word +
          tokens(i + 1).word +
          tokens(i + 2).word +
          tokens(i + 3).word
        output += tokenFactory.makeToken(word, tokens(i).beginPosition, word.length)
        i += 4

      // - or +
      } else if(i < tokens.length - 1) {
        val crt = tokens(i)
        val nxt = tokens(i + 1)

        // trailing +
        if(crt.endPosition == nxt.beginPosition &&
          ! isParen(crt.word) && nxt.word == "+"){
          val word = crt.word + nxt.word
          output += tokenFactory.makeToken(word, crt.beginPosition, word.length)
          i += 2
        }

        // trailing -
        else if(crt.endPosition == nxt.beginPosition &&
          (i + 2 >= tokens.length || nxt.endPosition != tokens(i + 2).beginPosition) &&
          ! isParen(crt.word) && nxt.word == "-"){
          val word = crt.word + nxt.word
          output += tokenFactory.makeToken(word, crt.beginPosition, word.length)
          i += 2
        }

        // preceding -
        else if(crt.endPosition == nxt.beginPosition &&
          (i == 0 || crt.beginPosition != tokens(i - 1).endPosition) &&
          ! isParen(nxt.word) && crt.word() == "-"){
          val word = crt.word + nxt.word
          output += tokenFactory.makeToken(word, crt.beginPosition, word.length)
          i += 2

        // nothing interesting
        } else {
          output += tokens(i)
          i += 1
        }

      // nothing interesting
      } else {
        output += tokens(i)
        i += 1
      }
    }
    output.toArray
  }

}

object BioNLPTokenizerPostProcessor {
  val tokenFactory = new CoreLabelTokenFactory()

  val DISCARD_STANDALONE_DASHES = true

  val VALID_DASH_SUFFIXES = Set(
    "\\w+ed", "\\w+ing", // tokenize for all suffix verbs, e.g., "ABC-mediated"
    "\\w+ation", // tokenize for all nominalization of simple events, e.g., "p53-phosphorylation"
    "(in)?dependent", "deficient", "response", "protein", "by", "specific", "like",
    "inducible", "responsive", "gene", "mRNA", "transcription", "cytoplasmic",
    "sensitive", "bound", "driven", "positive", "negative", "dominant",
    "family", "resistant", "activity", "proximal", "defective",
    "selective", "reporter", "fragment", "rich", "expression", // new suffixes from BC2
    "mechanisms?", "agonist", "heterozygous", "homozygous")

  // "non" is excluded from this set; it should be tokenized because it drives negation detection
  val COMMON_PREFIXES = Set("anti", "auto", "bi", "co", "de", "dis", "extra", "homo", "hetero", "hyper", "macro", "micro", "mono", "omni", "over", "poly", "pre", "post", "re", "semi", "sub", "super", "trans", "under")

  val dashSuffixes = mkDashSuffixes

  // A valid protein name must start with a letter, and contain at least 3 \w characters overall
  val VALID_PROTEIN = "[a-z][\\w\\-][\\w\\-]+"
  val VALID_PROTEIN_NO_DASH = "[a-z][\\w][\\w]+"
  val VALID_COMPLEX_SEPARATOR_PATTERN = Pattern.compile("[/\\-]")

  val SINGLESLASH_PATTERN = Pattern.compile(s"($VALID_PROTEIN)(/)($VALID_PROTEIN)", Pattern.CASE_INSENSITIVE)
  val SINGLEDASH_PATTERN = Pattern.compile(s"($VALID_PROTEIN_NO_DASH)(\\-)($VALID_PROTEIN_NO_DASH)", Pattern.CASE_INSENSITIVE)

  val SITE1_PATTERN = Pattern.compile("[ACDEFGHIKLMNQRSTVWY]\\d+", Pattern.CASE_INSENSITIVE)
  val SITE2 = "glycine|phenylalanine|leucine|serine|tyrosine|cysteine|tryptophan|proline|histidine|arginine|soleucine|methionine|threonine|asparagine|lysine|serine|arginine|valine|alanine|aspartate|glutamate|glycine"
  val SITE3 = "Ala|Arg|Asn|Asp|Cys|Gln|Glu|Gly|His|Ile|Leu|Lys|Met|Phe|Pro|Ser|Thr|Trp|Tyr|Val"
  val SITE2_PATTERN = Pattern.compile(SITE2, Pattern.CASE_INSENSITIVE)
  val SITE3_PATTERN = Pattern.compile(s"($SITE3)\\d*", Pattern.CASE_INSENSITIVE)
  val MUTATION1_PATTERN = Pattern.compile("[ACDEFGHIKLMNQRSTVWY]\\d+[ACDEFGHIKLMNPQRSTVWY]", Pattern.CASE_INSENSITIVE)
  val MUTATION2_PATTERN = Pattern.compile("P\\d+[ACDEFGHIKLMNPQRSTVWYacdefghiklmnpqrstvwy]")
  val MUTATION3_PATTERN = Pattern.compile("(Ala|Arg|Asn|Asp|Cys|Gln|Glu|Gly|His|Ile|Leu|Lys|Met|Phe|Pro|Ser|Thr|Trp|Tyr|Val)\\d+(Ala|Arg|Asn|Asp|Cys|Gln|Glu|Gly|His|Ile|Leu|Lys|Met|Phe|Pro|Ser|Thr|Trp|Tyr|Val)", Pattern.CASE_INSENSITIVE)
  val MODIFICATIONS = Set(SITE1_PATTERN, SITE2_PATTERN, SITE3_PATTERN, MUTATION1_PATTERN, MUTATION2_PATTERN, MUTATION3_PATTERN)

  val sitePrefixes = Pattern.compile(s"($SITE2|$SITE3)(-)([\\w/]+)", Pattern.CASE_INSENSITIVE)

  val PARENS = Set("(", ")", "[", "]")

  val COMPLEX = Pattern.compile("complex|dimer|heterodimer")
  val MUTANT = Pattern.compile("mutant|mutants|mutation|mutations")

  val MEASUREMENT_UNIT_WITHSLASH = Pattern.compile("\\w+/(ml|l|cm|m)", Pattern.CASE_INSENSITIVE)

  def isParen(s:String) = PARENS.contains(s)

  def isMeasurementUnit(s:String):Boolean = MEASUREMENT_UNIT_WITHSLASH.matcher(s).matches()

  def isComplex(word:String):Boolean = {
    val m = COMPLEX.matcher(word)
    m.matches()
  }
  def isMutant(word:String):Boolean = {
    val m = MUTANT.matcher(word)
    m.matches()
  }

  def isValidProtein(word:String):Boolean = {
    val m = Pattern.compile(VALID_PROTEIN, Pattern.CASE_INSENSITIVE).matcher(word)
    m.matches()
  }

  def countConnectingTokens(tokens:Array[CoreLabel], offset:Int, howManyConnecting:MutableNumber[Int]):Boolean = {
    var i = offset
    while(i < tokens.length - 1 && isConnectingToken(tokens(i).word()) && // found connecting token(s) that CoreNLP tokenized too aggressively
      tokens(i - 1).endPosition == tokens(i).beginPosition && // attached to the previous token
      tokens(i).endPosition == tokens(i + 1).beginPosition) { // attached to the next token
      howManyConnecting.value += 1
      i += 1
    }

    howManyConnecting.value > 0
  }

  def isConnectingToken(word:String):Boolean = {
    // is "/" or "-" or "-" followed by some digit(s)
    if(Pattern.compile("[\\-/]|\\-\\d+", Pattern.CASE_INSENSITIVE).matcher(word).matches())
      return true

    // starts with "-" digit+ "-" or "/"
    if(Pattern.compile("^\\-\\d+[\\-/]", Pattern.CASE_INSENSITIVE).matcher(word).find())
      return true

    false
  }

  def mkDashSuffixes:Pattern = {
    val allSuffixes = makeRegexOr(VALID_DASH_SUFFIXES)
    val allSuffixesRegex = "([\\w/]+)(-)(" + allSuffixes + ")"
    Pattern.compile(allSuffixesRegex, Pattern.CASE_INSENSITIVE)
  }

  def makeRegexOr(pieces: Set[String]):String = {
    val suffixBuilder = new StringBuilder()
    for (suffix <- pieces) {
      if (suffixBuilder.nonEmpty) suffixBuilder.append("|")
      suffixBuilder.append("(" + suffix + ")")
    }
    suffixBuilder.toString()
  }
}
