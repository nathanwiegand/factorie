package cc.factorie.app.nlp.ner
import cc.factorie._
import cc.factorie.app.nlp._
import java.io.File
import cc.factorie.util.{BinarySerializer, CubbieConversions}
import cc.factorie.optimize.LikelihoodExample

/** A simple named entity recognizer, trained on Ontonotes data.
    It does not have sufficient features to be state-of-the-art. */
class NER2 extends DocumentAnnotator {
  def this(filename: String) = { this(); deserialize(filename) }

  object FeaturesDomain extends CategoricalTensorDomain[String]
  class FeaturesVariable(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = FeaturesDomain
    override def skipNonCategories = true
  }
  
  class IndependentModel extends TemplateModel with Parameters {
    val evidence = this += new DotTemplateWithStatistics2[BilouOntonotesNerLabel, FeaturesVariable] {
      val weights = Weights(new la.DenseTensor2(BilouOntonotesNerDomain.size, FeaturesDomain.dimensionSize))
      def unroll1(label:BilouOntonotesNerLabel) = Factor(label, label.token.attr[FeaturesVariable])
      def unroll2(token:FeaturesVariable) = throw new Error("FeaturesVariable values shouldn't be inferred.")
    }
  }
  val model1 = new IndependentModel
  
  // The model
  class Model extends TemplateModel with Parameters {
    // Bias term on each individual label
    val bias = this += new DotTemplateWithStatistics1[BilouOntonotesNerLabel] {
      val weights = Weights(new la.DenseTensor1(BilouOntonotesNerDomain.size))
    }
    // Transition factors between two successive labels
    val markov = this += new DotTemplateWithStatistics2[BilouOntonotesNerLabel, BilouOntonotesNerLabel] {
      val weights = Weights(new la.DenseTensor2(BilouOntonotesNerDomain.size, BilouOntonotesNerDomain.size))
      def unroll1(label:BilouOntonotesNerLabel) = if (label.token.hasPrev) Factor(label.token.prev.attr[BilouOntonotesNerLabel], label) else Nil
      def unroll2(label:BilouOntonotesNerLabel) = Nil //if (label.token.hasNext) Factor(label, label.token.next.attr[BilouOntonotesNerLabel]) else Nil // Make this feedforward
    }
    // Factor between label and observed token
    val evidence = this += new DotTemplateWithStatistics2[BilouOntonotesNerLabel, FeaturesVariable] {
      val weights = Weights(new la.DenseTensor2(BilouOntonotesNerDomain.size, FeaturesDomain.dimensionSize))
      def unroll1(label:BilouOntonotesNerLabel) = Factor(label, label.token.attr[FeaturesVariable])
      def unroll2(token:FeaturesVariable) = throw new Error("FeaturesVariable values shouldn't be inferred.")
    }
    // Factor between this label and the previous label with a Token having the same spelling, with the statistics comparing the label values with the BILOU prefixes removed
    val history = this += new DotTemplate2[BilouOntonotesNerLabel,BilouOntonotesNerLabel] {
      val weights = Weights(new la.DenseTensor2(OntonotesNerDomain.size, OntonotesNerDomain.size))
      override def statistics(v1:BilouOntonotesNerLabel#Value, v2:BilouOntonotesNerLabel#Value): la.Tensor = 
        OntonotesNerDomain(BilouOntonotesNerDomain.bilouSuffixIntValue(v1.intValue)) outer  OntonotesNerDomain(BilouOntonotesNerDomain.bilouSuffixIntValue(v2.intValue))
      def unroll1(label:BilouOntonotesNerLabel) = Nil
      def unroll2(label:BilouOntonotesNerLabel) = predictionHistory.mostFrequentLabel(label.token) match { case l:BilouOntonotesNerLabel => Factor(l, label); case _ => Nil }
    }
  }
  val model = new Model
  // The training objective
  val objective = new HammingTemplate[BilouOntonotesNerLabel]

  // Methods of DocumentAnnotator
  override def tokenAnnotationString(token:Token): String = token.attr[BilouOntonotesNerLabel].categoryValue
  def prereqAttrs: Iterable[Class[_]] = List(classOf[Token])
  def postAttrs: Iterable[Class[_]] = List(classOf[BilouOntonotesNerLabel])
  var predictForward = true
  def process1(document:Document): Document = {
    if (document.tokenCount > 0) {
      val alreadyHadFeatures = document.hasAnnotation(classOf[FeaturesVariable])
      if (!alreadyHadFeatures) addFeatures(document)
      for (token <- document.tokens) if (token.attr[BilouOntonotesNerLabel] eq null) token.attr += new BilouOntonotesNerLabel(token, "O")
      if (predictForward) forwardPredictDocument(document)
      else for (sentence <- document.sentences if sentence.tokens.size > 0) BP.inferChainMax(sentence.tokens.map(_.attr[BilouOntonotesNerLabel]).toSeq, model)
      if (!alreadyHadFeatures) { document.annotators.remove(classOf[FeaturesVariable]); for (token <- document.tokens) token.attr.remove[FeaturesVariable] }
    }
    document
  }
  
  // Prediction history
  val predictionHistory = new HashedTokenQueue(200)
  // Returns null if there is no matching previous token
//  def previousPrediction(token:Token): BilouOntonotesNerLabel = {
//    val history: Seq[Token] = predictionHistory.filterByToken(token)
//    val mostFrequent: Token = history.groupBy(_.attr[BilouOntonotesNerLabel].categoryValue).maxBy(_._2.size)._2.head
//    if (history.length == 0) null else mostFrequent.attr[BilouOntonotesNerLabel]
//  }

  def forwardPredictToken(token:Token): Unit = {
    val label = token.attr[BilouOntonotesNerLabel]
    MaximizeDiscrete(label, model)
    predictionHistory += token
  }
  def forwardPredictDocument(document:Document): Unit = {
    //predictionHistory.clear()
    for (token <- document.tokens) forwardPredictToken(token)
    //predictionHistory.clear()
  }
  
  // Feature creation
  def addFeatures(document:Document): Unit = {
    document.annotators(classOf[FeaturesVariable]) = this
    import cc.factorie.app.strings.simplifyDigits
    for (token <- document.tokens) {
      val features = new FeaturesVariable(token)
      token.attr += features
      val rawWord = token.string
      val word = simplifyDigits(rawWord).toLowerCase
      features += "W="+word
      features += "SHAPE="+cc.factorie.app.strings.stringShape(rawWord, 2)
      if (token.isPunctuation) features += "PUNCTUATION"
      if (lexicon.NumberWords.contains(word)) features += "#WORD"
    }
    for (section <- document.sections)
      cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(section.tokens, (t:Token)=>t.attr[FeaturesVariable], Seq(0), Seq(-1), Seq(-2), Seq(1), Seq(2), Seq(0,0))
  }
  
  def sampleOutputString(tokens:Iterable[Token]): String = {
    val sb = new StringBuffer
    for (token <- tokens)
      sb.append("%20s %-20s  %10s %10s\n".format(token.string, token.lemmaString, token.attr[BilouOntonotesNerLabel].target.categoryValue, token.attr[BilouOntonotesNerLabel].categoryValue))
    sb.toString
  }
  
  def segmentEvaluationString(labels:IndexedSeq[BilouOntonotesNerLabel]): String = {
    val se = new app.chain.SegmentEvaluation[BilouOntonotesNerLabel]("(B|U)-", "(I|L)-", BilouOntonotesNerDomain)
    se += labels
    se.summaryString
  }
  
  def trainIndependent(trainDocs:Iterable[Document], testDocs:Iterable[Document]): Unit = {
    def labels(docs:Iterable[Document]): Iterable[BilouOntonotesNerLabel] = docs.flatMap(doc => doc.tokens.map(_.attr[BilouOntonotesNerLabel]))
    def predict(labels:Iterable[BilouOntonotesNerLabel]): Unit = for (label <- labels) MaximizeDiscrete(label, model1)
    val trainer = new optimize.OnlineTrainer(model1.parameters, maxIterations=1)
    for (iter <- 1 until 5) {
      trainer.processExamples(labels(trainDocs).map(label => new optimize.DiscreteLikelihoodExample(label, model1)))
      predict(labels(trainDocs ++ testDocs))
      println("Some independent training data"); println(sampleOutputString(trainDocs.head.tokens.drop(100).take(200)))
      println("Some independent testing data"); println(sampleOutputString(testDocs.head.tokens.drop(100).take(200)))
      println("Train accuracy "+objective.accuracy(labels(trainDocs)))
      println("Test  accuracy "+objective.accuracy(labels(testDocs)))
    }
  }
  
  // Parameter estimation
  def train(trainDocs:Iterable[Document], testDocs:Iterable[Document]): Unit = {
    def labels(docs:Iterable[Document]): Iterable[BilouOntonotesNerLabel] = docs.flatMap(doc => doc.tokens.map(_.attr[BilouOntonotesNerLabel]))
    //trainIndependent(trainDocs, testDocs)
    val predictor = new DiscreteProposalMaximizer(model, objective) {
      override def process1(context:DiscreteVar): DiffList = {
        val result = super.process1(context)
        val label = context.asInstanceOf[BilouOntonotesNerLabel]
        predictionHistory += label.token
        result
      }
    }
    val learner = new optimize.SampleRankTrainer(predictor, new optimize.AdaGrad)
    for (iteration <- 1 until 3) {
      learner.processContexts(labels(trainDocs))
      trainDocs.foreach(process(_)); println("Train accuracy "+objective.accuracy(labels(trainDocs)))
      testDocs.foreach(process(_));  println("Test  accuracy "+objective.accuracy(labels(testDocs)))
      println("Some training data"); println(sampleOutputString(trainDocs.head.tokens.drop(100).take(100)))
      println("Some testing data"); println(sampleOutputString(testDocs.head.tokens.drop(100).take(100)))
      println("Train accuracy "+objective.accuracy(labels(trainDocs)))
      println(segmentEvaluationString(labels(trainDocs).toIndexedSeq))
      println("Test  accuracy "+objective.accuracy(labels(testDocs)))
      println(segmentEvaluationString(labels(testDocs).toIndexedSeq))
    }
  }
  def train(trainFilename:String, testFilename:String): Unit = {
    val trainDocs = LoadOntonotes5.fromFilename(trainFilename, nerBilou=true)
    val testDocs = LoadOntonotes5.fromFilename(testFilename, nerBilou=true)
    
    // Testing Queue
    trainDocs.flatMap(_.tokens).take(5000).foreach(t => predictionHistory += t)
    //println("NER2.train"); println(predictionHistory); System.exit(0)
    
    (trainDocs ++ testDocs).foreach(addFeatures(_)) // Initialize all features to get parameter
    println("Training with %d features.".format(FeaturesDomain.dimensionSize))
    train(trainDocs, testDocs)
    FeaturesDomain.freeze()
  }
  
  // Serialization
  def serialize(filename: String) {
    import CubbieConversions._
    val file = new File(filename); if (file.getParentFile eq null) file.getParentFile.mkdirs()
    BinarySerializer.serialize(FeaturesDomain.dimensionDomain, model, file)
  }
  def deserialize(filename: String) {
    import CubbieConversions._
    val file = new File(filename)
    assert(file.exists(), "Trying to load non-existent file: '" +file)
    BinarySerializer.deserialize(FeaturesDomain.dimensionDomain, model, file)
  }
  
  
  /** A queue of tokens, FILO, that will dequeue to maintain size less than maxSize,
      and which also has efficient acess to its elements keyed by Token.string. */
  class HashedTokenQueue(val maxSize:Int) extends scala.collection.mutable.Queue[Token] {
    private val hash = new scala.collection.mutable.HashMap[String,scala.collection.mutable.Queue[Token]]
    var debugPrintCount = 0
    /** Return a collection of Tokens with string value equal to the argument's string. */
    def filterByString(string:String): Seq[Token] = if (java.lang.Character.isUpperCase(string(0))) {
      //hash.getOrElse(string, Nil) else Nil
      val tokens = if (hash.contains(string)) hash(string) else return Nil
      tokens
    } else Nil
    def filterByToken(token:Token): Seq[Token] = {
      val tokens = filterByString(token.string)
      if ((debugPrintCount % 10000 == 0) && tokens.length > 0) println("HashedTokenQueue %20s %20s  %-20s  true=%-10s  freq=%-5s  %s".format(token.getPrev.map(_.string).getOrElse(null), token.string, token.getNext.map(_.string).getOrElse(null), token.attr[BilouOntonotesNerLabel].target.categoryValue, mostFrequentLabel(tokens).baseCategoryValue, tokens.map(_.attr[BilouOntonotesNerLabel].categoryValue).mkString(" ")))
      debugPrintCount += 1
      tokens
    }
    // A label having the most frequent value of all labels associated with all Tokens having the same string as the given Token, or null if there is no Token with matching string
    def mostFrequentLabel(token:Token): BilouOntonotesNerLabel = filterByToken(token) match {
      case Nil => null
      case tokens: Seq[Token] => tokens.groupBy(_.attr[BilouOntonotesNerLabel].baseCategoryValue).maxBy(_._2.size)._2.head.attr[BilouOntonotesNerLabel]
    }
    private def mostFrequentLabel(tokens:Seq[Token]): BilouOntonotesNerLabel = tokens.groupBy(_.attr[BilouOntonotesNerLabel].baseCategoryValue).maxBy(_._2.size)._2.head.attr[BilouOntonotesNerLabel]

    // Add a Token to the Queue and also to the internal hash
    override def +=(token:Token): this.type = {
      val str = token.string
      assert(lexicon.StopWords.contents.contains("i"))
      assert(lexicon.StopWords.contains("i"))
      assert(lexicon.StopWords.contains("The"))
      if (java.lang.Character.isUpperCase(str(0)) && !lexicon.StopWords.containsWord(str.toLowerCase)) { // Only add capitalized, non-stopword Tokens
        super.+=(token)
        hash.getOrElseUpdate(token.string, new scala.collection.mutable.Queue[Token]) += token
        if (length > maxSize) dequeue
      }
      this
    }
    override def dequeue(): Token = {
      val token = super.dequeue()
      val q2 = hash(token.string)
      val t2 = q2.dequeue(); assert(t2 eq token)
      if (q2.size == 0) hash -= token.string
      token
    }
    override def clear(): Unit = {
      super.clear()
      hash.clear()
    }
    override def toString: String = {
      (for (token <- this) yield
          "%s = %s".format(token.string, filterByToken(token).map(token => OntonotesNerDomain(BilouOntonotesNerDomain.bilouSuffixIntValue(token.attr[BilouOntonotesNerLabel].intValue)).category).mkString(" "))
      ).mkString("\n")
    }
  }

}

object NER2 {
  def main(args:Array[String]): Unit = {
    if (args.length != 3) throw new Error("Usage: trainfile testfile savemodelfile")
    val ner = new NER2
    ner.train(args(0), args(1))
    ner.serialize(args(2))
  }
}
