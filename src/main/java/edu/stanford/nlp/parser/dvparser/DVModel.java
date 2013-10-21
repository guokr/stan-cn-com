package edu.stanford.nlp.parser.dvparser;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ejml.simple.*;
import org.ejml.data.DenseMatrix64F;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.parser.lexparser.BinaryGrammar;
import edu.stanford.nlp.parser.lexparser.BinaryRule;
import edu.stanford.nlp.parser.lexparser.Options;
import edu.stanford.nlp.parser.lexparser.UnaryGrammar;
import edu.stanford.nlp.parser.lexparser.UnaryRule;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.ErasureUtils;
import edu.stanford.nlp.util.Function;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.Maps;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.MapFactory;
import edu.stanford.nlp.util.TwoDimensionalMap;
import edu.stanford.nlp.util.TwoDimensionalSet;

public class DVModel implements Serializable {
  // The following data structures are all transient because the
  // SimpleMatrix object is not Serializable.  We read and write them
  // in specialized readObject and writeObject calls.

  // Maps from basic category to the matrix transformation matrices for
  // binary nodes and unary nodes.
  // The indices are the children categories.  For binaryTransform, for
  // example, we have a matrix for each type of child that appears.
  transient public TwoDimensionalMap<String, String, SimpleMatrix> binaryTransform;
  transient public Map<String, SimpleMatrix> unaryTransform;

  // score matrices for each node type
  transient public TwoDimensionalMap<String, String, SimpleMatrix> binaryScore;
  transient public Map<String, SimpleMatrix> unaryScore;

  transient public Map<String, SimpleMatrix> wordVectors;

  // cache these for easy calculation of "theta" parameter size
  int numBinaryMatrices, numUnaryMatrices;
  int binaryTransformSize, unaryTransformSize;
  int binaryScoreSize, unaryScoreSize;

  Options op;

  final int numCols;
  final int numRows;

  // we just keep this here for convenience
  transient SimpleMatrix identity;

  // the seed we used to use was 19580427
  Random rand;

  static final String UNKNOWN_WORD = "*UNK*";
  static final String UNKNOWN_NUMBER = "*NUM*";
  static final String UNKNOWN_CAPS = "*CAPS*";
  static final String UNKNOWN_CHINESE_YEAR = "*ZH_YEAR*";
  static final String UNKNOWN_CHINESE_NUMBER = "*ZH_NUM*";
  static final String UNKNOWN_CHINESE_PERCENT = "*ZH_PERCENT*";

  static final String START_WORD = "*START*";
  static final String END_WORD = "*END*";

  static final boolean TRAIN_WORD_VECTORS = true;

  private static final Function<SimpleMatrix, DenseMatrix64F> convertSimpleMatrix = new Function<SimpleMatrix, DenseMatrix64F>() {
    public DenseMatrix64F apply(SimpleMatrix matrix) {
      return matrix.getMatrix();
    }
  };

  private static final Function<DenseMatrix64F, SimpleMatrix> convertDenseMatrix = new Function<DenseMatrix64F, SimpleMatrix>() {
    public SimpleMatrix apply(DenseMatrix64F matrix) {
      return SimpleMatrix.wrap(matrix);
    }
  };

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();

    TwoDimensionalMap<String, String, DenseMatrix64F> binaryT = ErasureUtils.uncheckedCast(in.readObject());
    binaryTransform = TwoDimensionalMap.treeMap();
    binaryTransform.addAll(binaryT, convertDenseMatrix);
    
    Map<String, DenseMatrix64F> unaryT = ErasureUtils.uncheckedCast(in.readObject());
    unaryTransform = Generics.newTreeMap();
    Maps.addAll(unaryTransform, unaryT, convertDenseMatrix);

    TwoDimensionalMap<String, String, DenseMatrix64F> binaryS = ErasureUtils.uncheckedCast(in.readObject());
    binaryScore = TwoDimensionalMap.treeMap();
    binaryScore.addAll(binaryS, convertDenseMatrix);

    Map<String, DenseMatrix64F> unaryS = ErasureUtils.uncheckedCast(in.readObject());
    unaryScore = Generics.newTreeMap();
    Maps.addAll(unaryScore, unaryS, convertDenseMatrix);

    Map<String, DenseMatrix64F> wordV = ErasureUtils.uncheckedCast(in.readObject());
    wordVectors = Generics.newTreeMap();
    Maps.addAll(wordVectors, wordV, convertDenseMatrix);

    identity = SimpleMatrix.identity(numRows);    
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject();

    TwoDimensionalMap<String, String, DenseMatrix64F> binaryT = TwoDimensionalMap.treeMap();
    binaryT.addAll(binaryTransform, convertSimpleMatrix);
    out.writeObject(binaryT);
    
    Map<String, DenseMatrix64F> unaryT = Generics.newTreeMap();
    Maps.addAll(unaryT, unaryTransform, convertSimpleMatrix);
    out.writeObject(unaryT);

    TwoDimensionalMap<String, String, DenseMatrix64F> binaryS = TwoDimensionalMap.treeMap();
    binaryS.addAll(binaryScore, convertSimpleMatrix);
    out.writeObject(binaryS);
    
    Map<String, DenseMatrix64F> unaryS = Generics.newTreeMap();
    Maps.addAll(unaryS, unaryScore, convertSimpleMatrix);
    out.writeObject(unaryS);

    Map<String, DenseMatrix64F> wordV = Generics.newHashMap();
    Maps.addAll(wordV, wordVectors, convertSimpleMatrix);
    out.writeObject(wordV);
  }


  /**
   * @param op the parameters of the parser
   */
  public DVModel(Options op, Index<String> stateIndex, UnaryGrammar unaryGrammar, BinaryGrammar binaryGrammar) {
    this.op = op;

    rand = new Random(op.trainOptions.dvSeed);
    
    readWordVectors();

    // Binary matrices will be n*2n+1, unary matrices will be n*n+1
    numRows = op.lexOptions.numHid;
    numCols = op.lexOptions.numHid;

    // Build one matrix for each basic category.
    // We assume that each state that has the same basic
    // category is using the same transformation matrix.
    // Use TreeMap for because we want values to be
    // sorted by key later on when building theta vectors
    binaryTransform = TwoDimensionalMap.treeMap();
    unaryTransform = Generics.newTreeMap();
    binaryScore = TwoDimensionalMap.treeMap();
    unaryScore = Generics.newTreeMap();

    numBinaryMatrices = 0;
    numUnaryMatrices = 0;
    binaryTransformSize = numRows * (numCols * 2 + 1);
    unaryTransformSize = numRows * (numCols + 1);
    binaryScoreSize = numCols;
    unaryScoreSize = numCols;
    
    if (op.trainOptions.useContextWords) {
      binaryTransformSize += numRows * numCols * 2;
      unaryTransformSize += numRows * numCols * 2;
    }

    identity = SimpleMatrix.identity(numRows);
    
    for (UnaryRule unaryRule : unaryGrammar) {
      // only make one matrix for each parent state, and only use the
      // basic category for that      
      String childState = stateIndex.get(unaryRule.child);
      String childBasic = basicCategory(childState);

      addRandomUnaryMatrix(childBasic);
    }

    for (BinaryRule binaryRule : binaryGrammar) {
      // only make one matrix for each parent state, and only use the
      // basic category for that
      String leftState = stateIndex.get(binaryRule.leftChild);
      String leftBasic = basicCategory(leftState);
      String rightState = stateIndex.get(binaryRule.rightChild);
      String rightBasic = basicCategory(rightState);

      addRandomBinaryMatrix(leftBasic, rightBasic);
    }
  }

  public DVModel(TwoDimensionalMap<String, String, SimpleMatrix> binaryTransform, Map<String, SimpleMatrix> unaryTransform,
                 TwoDimensionalMap<String, String, SimpleMatrix> binaryScore, Map<String, SimpleMatrix> unaryScore,
                 Map<String, SimpleMatrix> wordVectors, Options op) {
    this.op = op;
    this.binaryTransform = binaryTransform;
    this.unaryTransform = unaryTransform;
    this.binaryScore = binaryScore;
    this.unaryScore = unaryScore;
    this.wordVectors = wordVectors;

    this.numBinaryMatrices = binaryTransform.size();
    this.numUnaryMatrices = unaryTransform.size();
    if (numBinaryMatrices > 0) {
      this.binaryTransformSize = binaryTransform.iterator().next().getValue().getNumElements();
      this.binaryScoreSize = binaryScore.iterator().next().getValue().getNumElements();
    } else {
      this.binaryTransformSize = 0;
      this.binaryScoreSize = 0;
    }
    if (numUnaryMatrices > 0) {
      this.unaryTransformSize = unaryTransform.values().iterator().next().getNumElements();
      this.unaryScoreSize = unaryScore.values().iterator().next().getNumElements();
    } else {
      this.unaryTransformSize = 0;
      this.unaryScoreSize = 0;
    }

    this.numRows = op.lexOptions.numHid;
    this.numCols = op.lexOptions.numHid;

    this.identity = SimpleMatrix.identity(numRows);    

    this.rand = new Random(op.trainOptions.dvSeed);
  }

  /**
   * Creates a random context matrix.  This will be numRows x
   * 2*numCols big.  These can be appended to the end of either a
   * unary or binary transform matrix to get the transform matrix
   * which uses context words.
   */
  private SimpleMatrix randomContextMatrix() {
    SimpleMatrix matrix = new SimpleMatrix(numRows, numCols * 2);
    matrix.insertIntoThis(0, 0, identity.scale(op.trainOptions.scalingForInit * 0.1));
    matrix.insertIntoThis(0, numCols, identity.scale(op.trainOptions.scalingForInit * 0.1));
    matrix = matrix.plus(SimpleMatrix.random(numRows,numCols * 2,-1.0/Math.sqrt((double)numCols * 100.0),1.0/Math.sqrt((double)numCols * 100.0),rand));
    return matrix;
  }

  /**
   * Create a random transform matrix based on the initialization
   * parameters.  This will be numRows x numCols big.  These can be
   * plugged into either unary or binary transform matrices.
   */
  private SimpleMatrix randomTransformMatrix() {
    SimpleMatrix matrix;
    switch (op.trainOptions.transformMatrixType) {
    case DIAGONAL:
      matrix = SimpleMatrix.random(numRows,numCols,-1.0/Math.sqrt((double)numCols * 100.0),1.0/Math.sqrt((double)numCols * 100.0),rand).plus(identity);
      break;
    case RANDOM:
      matrix = SimpleMatrix.random(numRows,numCols,-1.0/Math.sqrt((double)numCols),1.0/Math.sqrt((double)numCols),rand);
      break;
    case OFF_DIAGONAL:
      matrix = SimpleMatrix.random(numRows,numCols,-1.0/Math.sqrt((double)numCols * 100.0),1.0/Math.sqrt((double)numCols * 100.0),rand).plus(identity);
      for (int i = 0; i < numCols; ++i) {
        int x = rand.nextInt(numCols);
        int y = rand.nextInt(numCols);
        int scale = rand.nextInt(3) - 1;  // -1, 0, or 1
        matrix.set(x, y, matrix.get(x, y) + scale);
      }
      break;
    case RANDOM_ZEROS:
      matrix = SimpleMatrix.random(numRows,numCols,-1.0/Math.sqrt((double)numCols * 100.0),1.0/Math.sqrt((double)numCols * 100.0),rand).plus(identity);
      for (int i = 0; i < numCols; ++i) {
        int x = rand.nextInt(numCols);
        int y = rand.nextInt(numCols);
        matrix.set(x, y, 0.0);
      }
      break;
    default:
      throw new IllegalArgumentException("Unexpected matrix initialization type " + op.trainOptions.transformMatrixType);
    }
    return matrix;
  }

  public void addRandomUnaryMatrix(String childBasic) {
    if (unaryTransform.get(childBasic) != null) {
      return;
    }
    
    ++numUnaryMatrices;

    // scoring matrix
    SimpleMatrix score = SimpleMatrix.random(1, numCols, -1.0/Math.sqrt((double)numCols),1.0/Math.sqrt((double)numCols),rand);
    unaryScore.put(childBasic, score.scale(op.trainOptions.scalingForInit));      
    
    SimpleMatrix transform;
    if (op.trainOptions.useContextWords) {
      transform = new SimpleMatrix(numRows, numCols * 3 + 1);
      // leave room for bias term
      transform.insertIntoThis(0,numCols + 1, randomContextMatrix());
    } else {
      transform = new SimpleMatrix(numRows, numCols + 1);
    }
    SimpleMatrix unary = randomTransformMatrix();
    transform.insertIntoThis(0, 0, unary);
    unaryTransform.put(childBasic, transform.scale(op.trainOptions.scalingForInit));
  }

  public void addRandomBinaryMatrix(String leftBasic, String rightBasic) {
    if (binaryTransform.get(leftBasic, rightBasic) != null) {
      return;
    }
      
    ++numBinaryMatrices;

    // scoring matrix
    SimpleMatrix score = SimpleMatrix.random(1, numCols, -1.0/Math.sqrt((double)numCols),1.0/Math.sqrt((double)numCols),rand);
    binaryScore.put(leftBasic, rightBasic, score.scale(op.trainOptions.scalingForInit));      
    
    SimpleMatrix binary;
    if (op.trainOptions.useContextWords) {
      binary = new SimpleMatrix(numRows, numCols * 4 + 1);
      // leave room for bias term
      binary.insertIntoThis(0,numCols*2+1, randomContextMatrix());
    } else {
      binary = new SimpleMatrix(numRows, numCols * 2 + 1);
    }
    SimpleMatrix left = randomTransformMatrix();
    SimpleMatrix right = randomTransformMatrix();
    binary.insertIntoThis(0, 0, left);
    binary.insertIntoThis(0, numCols, right);
    binaryTransform.put(leftBasic, rightBasic, binary.scale(op.trainOptions.scalingForInit));
  }
  
  public void setRulesForTrainingSet(List<Tree> sentences, Map<Tree, byte[]> compressedTrees) {
    TwoDimensionalSet<String, String> binaryRules = TwoDimensionalSet.treeSet();
    Set<String> unaryRules = new HashSet<String>();
    Set<String> words = new HashSet<String>();
    for (Tree sentence : sentences) {
      searchRulesForBatch(binaryRules, unaryRules, words, sentence);

      for (Tree hypothesis : CacheParseHypotheses.convertToTrees(compressedTrees.get(sentence))) {
        searchRulesForBatch(binaryRules, unaryRules, words, hypothesis);
      }
    }

    for (Pair<String, String> binary : binaryRules) {
      addRandomBinaryMatrix(binary.first, binary.second);
    }
    for (String unary : unaryRules) {
      addRandomUnaryMatrix(unary);
    }

    filterRulesForBatch(binaryRules, unaryRules, words);
  }

  /**
   * Filters the transform and score rules so that we only have the
   * ones which appear in the trees given
   */
  public void filterRulesForBatch(Collection<Tree> trees) {
    TwoDimensionalSet<String, String> binaryRules = TwoDimensionalSet.treeSet();
    Set<String> unaryRules = new HashSet<String>();
    Set<String> words = new HashSet<String>();
    for (Tree tree : trees) {
      searchRulesForBatch(binaryRules, unaryRules, words, tree);
    }

    filterRulesForBatch(binaryRules, unaryRules, words);
  }

  public void filterRulesForBatch(Map<Tree, byte[]> compressedTrees) {
    TwoDimensionalSet<String, String> binaryRules = TwoDimensionalSet.treeSet();
    Set<String> unaryRules = new HashSet<String>();
    Set<String> words = new HashSet<String>();
    for (Map.Entry<Tree, byte[]> entry : compressedTrees.entrySet()) {
      searchRulesForBatch(binaryRules, unaryRules, words, entry.getKey());

      for (Tree hypothesis : CacheParseHypotheses.convertToTrees(entry.getValue())) {
        searchRulesForBatch(binaryRules, unaryRules, words, hypothesis);
      }
    }

    filterRulesForBatch(binaryRules, unaryRules, words);
  }

  public void filterRulesForBatch(TwoDimensionalSet<String, String> binaryRules, Set<String> unaryRules, Set<String> words) {
    TwoDimensionalMap<String, String, SimpleMatrix> newBinaryTransforms = TwoDimensionalMap.treeMap();
    TwoDimensionalMap<String, String, SimpleMatrix> newBinaryScores = TwoDimensionalMap.treeMap();
    for (Pair<String, String> binaryRule : binaryRules) {
      SimpleMatrix transform = binaryTransform.get(binaryRule.first(), binaryRule.second());
      if (transform != null) {
        newBinaryTransforms.put(binaryRule.first(), binaryRule.second(), transform);
      }
      SimpleMatrix score = binaryScore.get(binaryRule.first(), binaryRule.second());
      if (score != null) {
        newBinaryScores.put(binaryRule.first(), binaryRule.second(), score);
      }
      if ((transform == null && score != null) ||
          (transform != null && score == null)) {
        throw new AssertionError();
      }
    }
    binaryTransform = newBinaryTransforms;
    binaryScore = newBinaryScores;
    numBinaryMatrices = binaryTransform.size();

    Map<String, SimpleMatrix> newUnaryTransforms = Generics.newTreeMap();
    Map<String, SimpleMatrix> newUnaryScores = Generics.newTreeMap();
    for (String unaryRule : unaryRules) {
      SimpleMatrix transform = unaryTransform.get(unaryRule);
      if (transform != null) {
        newUnaryTransforms.put(unaryRule, transform);
      }
      SimpleMatrix score = unaryScore.get(unaryRule);
      if (score != null) {
        newUnaryScores.put(unaryRule, score);
      }
      if ((transform == null && score != null) ||
          (transform != null && score == null)) {
        throw new AssertionError();
      }
    }
    unaryTransform = newUnaryTransforms;
    unaryScore = newUnaryScores;
    numUnaryMatrices = unaryTransform.size();

    Map<String, SimpleMatrix> newWordVectors = Generics.newTreeMap();
    for (String word : words) {
      SimpleMatrix wordVector = wordVectors.get(word);
      if (wordVector != null) {
        newWordVectors.put(word, wordVector);
      }
    }
    wordVectors = newWordVectors;
  }

  private void searchRulesForBatch(TwoDimensionalSet<String, String> binaryRules,
                                   Set<String> unaryRules, Set<String> words,
                                   Tree tree) {
    if (tree.isLeaf()) {
      return;
    }
    if (tree.isPreTerminal()) {
      words.add(getVocabWord(tree.children()[0].value()));
      return;
    }
    Tree[] children = tree.children();
    if (children.length == 1) {
      unaryRules.add(basicCategory(children[0].value()));
      searchRulesForBatch(binaryRules, unaryRules, words, children[0]);
    } else if (children.length == 2) {
      binaryRules.add(basicCategory(children[0].value()),
                      basicCategory(children[1].value()));
      searchRulesForBatch(binaryRules, unaryRules, words, children[0]);
      searchRulesForBatch(binaryRules, unaryRules, words, children[1]);
    } else {
      throw new AssertionError("Expected a binarized tree");
    }
  }

  public String basicCategory(String category) {
    if (op.trainOptions.dvSimplifiedModel) {
      return "";
    } else {
      String basic = op.langpack().basicCategory(category);
      // TODO: if we can figure out what is going on with the grammar
      // compaction, perhaps we don't want this any more
      if (basic.length() > 0 && basic.charAt(0) == '@') {
        basic = basic.substring(1);
      }
      return basic;
    }
  }

  static final Pattern NUMBER_PATTERN = Pattern.compile("-?[0-9][-0-9,.:]*");

  static final Pattern CAPS_PATTERN = Pattern.compile("[a-zA-Z]*[A-Z][a-zA-Z]*");

  static final Pattern CHINESE_YEAR_PATTERN = Pattern.compile("[〇零一二三四五六七八九０１２３４５６７８９]{4}+年");

  static final Pattern CHINESE_NUMBER_PATTERN = Pattern.compile("(?:[〇０零一二三四五六七八九０１２３４５６７８９十百万千亿]+[点多]?)+");

  static final Pattern CHINESE_PERCENT_PATTERN = Pattern.compile("百分之[〇０零一二三四五六七八九０１２３４５６７８９十点]+");

  /**
   * Some word vectors are trained with DG representing number.  
   * We mix all of those into the unknown number vectors.
   */
  static final Pattern DG_PATTERN = Pattern.compile(".*DG.*");

  public void readWordVectors() {
    SimpleMatrix unknownNumberVector = null;
    SimpleMatrix unknownCapsVector = null;
    SimpleMatrix unknownChineseYearVector = null;
    SimpleMatrix unknownChineseNumberVector = null;
    SimpleMatrix unknownChinesePercentVector = null;

    wordVectors = Generics.newTreeMap();
    int numberCount = 0;
    int capsCount = 0;
    int chineseYearCount = 0;
    int chineseNumberCount = 0;
    int chinesePercentCount = 0;

    System.err.println("Reading in the word vector file: " + op.lexOptions.wordVectorFile);
    int dimOfWords = 0;
    boolean warned = false;
    for (String line : IOUtils.readLines(op.lexOptions.wordVectorFile, "utf-8")) {
      String[]  lineSplit = line.split("\\s+");
      String word = lineSplit[0];
      if (op.wordFunction != null) {
        word = op.wordFunction.apply(word);
      }
      dimOfWords = lineSplit.length - 1;
      if (op.lexOptions.numHid <= 0) {
        op.lexOptions.numHid = dimOfWords;
        System.err.println("Dimensionality of numHid not set.  The length of the word vectors in the given file appears to be " + dimOfWords);
      }
      // the first entry is the word itself
      // the other entries will all be entries in the word vector
      if (dimOfWords > op.lexOptions.numHid) {
        if (!warned) {
          warned = true;
          System.err.println("WARNING: Dimensionality of numHid parameter and word vectors do not match, deleting word vector dimensions to fit!");
        }
        dimOfWords = op.lexOptions.numHid;
      } else if (dimOfWords < op.lexOptions.numHid) {
        throw new RuntimeException("Word vectors file has dimension too small for requested numHid of " + op.lexOptions.numHid);
      }
      double vec[][] = new double[dimOfWords][1];
      for (int i = 1; i <= dimOfWords; i++) {
        vec[i-1][0] = Double.parseDouble(lineSplit[i]);
      }
      SimpleMatrix vector = new SimpleMatrix(vec);
      wordVectors.put(word, vector);

      // TODO: factor out all of these identical blobs
      if (op.trainOptions.unknownNumberVector && 
          (NUMBER_PATTERN.matcher(word).matches() || DG_PATTERN.matcher(word).matches())) {
        ++numberCount;
        if (unknownNumberVector == null) {
          unknownNumberVector = new SimpleMatrix(vector);
        } else {
          unknownNumberVector = unknownNumberVector.plus(vector);
        }
      }

      if (op.trainOptions.unknownCapsVector && CAPS_PATTERN.matcher(word).matches()) {
        ++capsCount;
        if (unknownCapsVector == null) {
          unknownCapsVector = new SimpleMatrix(vector);
        } else {
          unknownCapsVector = unknownCapsVector.plus(vector);
        }
      }

      if (op.trainOptions.unknownChineseYearVector && CHINESE_YEAR_PATTERN.matcher(word).matches()) {
        ++chineseYearCount;
        if (unknownChineseYearVector == null) {
          unknownChineseYearVector = new SimpleMatrix(vector);
        } else {
          unknownChineseYearVector = unknownChineseYearVector.plus(vector);
        }
      }

      if (op.trainOptions.unknownChineseNumberVector && 
          (CHINESE_NUMBER_PATTERN.matcher(word).matches() || DG_PATTERN.matcher(word).matches())) {
        ++chineseNumberCount;
        if (unknownChineseNumberVector == null) {
          unknownChineseNumberVector = new SimpleMatrix(vector);
        } else {
          unknownChineseNumberVector = unknownChineseNumberVector.plus(vector);
        }
      }

      if (op.trainOptions.unknownChinesePercentVector && CHINESE_PERCENT_PATTERN.matcher(word).matches()) {
        ++chinesePercentCount;
        if (unknownChinesePercentVector == null) {
          unknownChinesePercentVector = new SimpleMatrix(vector);
        } else {
          unknownChinesePercentVector = unknownChinesePercentVector.plus(vector);
        }
      }
    }

    String unkWord = op.trainOptions.unkWord;
    if (op.wordFunction != null) {
      unkWord = op.wordFunction.apply(unkWord);
    }
    SimpleMatrix unknownWordVector = wordVectors.get(unkWord);
    wordVectors.put(UNKNOWN_WORD, unknownWordVector);
    if (unknownWordVector == null) {
      throw new RuntimeException("Unknown word vector not specified in the word vector file");
    }
    
    if (op.trainOptions.unknownNumberVector) {
      if (numberCount > 0) {
        unknownNumberVector = unknownNumberVector.divide(numberCount);
      } else {
        unknownNumberVector = new SimpleMatrix(unknownWordVector);
      }
      wordVectors.put(UNKNOWN_NUMBER, unknownNumberVector);
    }
    
    if (op.trainOptions.unknownCapsVector) {
      if (capsCount > 0) {
        unknownCapsVector = unknownCapsVector.divide(capsCount);
      } else {
        unknownCapsVector = new SimpleMatrix(unknownWordVector);
      }
      wordVectors.put(UNKNOWN_CAPS, unknownCapsVector);
    }

    if (op.trainOptions.unknownChineseYearVector) {
      System.err.println("Matched " + chineseYearCount + " chinese year vectors");
      if (chineseYearCount > 0) {
        unknownChineseYearVector = unknownChineseYearVector.divide(chineseYearCount);
      } else {
        unknownChineseYearVector = new SimpleMatrix(unknownWordVector);
      }
      wordVectors.put(UNKNOWN_CHINESE_YEAR, unknownChineseYearVector);
    }

    if (op.trainOptions.unknownChineseNumberVector) {
      System.err.println("Matched " + chineseNumberCount + " chinese number vectors");
      if (chineseNumberCount > 0) {
        unknownChineseNumberVector = unknownChineseNumberVector.divide(chineseNumberCount);
      } else {
        unknownChineseNumberVector = new SimpleMatrix(unknownWordVector);
      }
      wordVectors.put(UNKNOWN_CHINESE_NUMBER, unknownChineseNumberVector);
    }

    if (op.trainOptions.unknownChinesePercentVector) {
      System.err.println("Matched " + chinesePercentCount + " chinese percent vectors");
      if (chinesePercentCount > 0) {
        unknownChinesePercentVector = unknownChinesePercentVector.divide(chinesePercentCount);
      } else {
        unknownChinesePercentVector = new SimpleMatrix(unknownWordVector);
      }
      wordVectors.put(UNKNOWN_CHINESE_PERCENT, unknownChinesePercentVector);
    }

    if (op.trainOptions.useContextWords) {
      SimpleMatrix start = SimpleMatrix.random(op.lexOptions.numHid, 1, -0.5, 0.5, rand);
      SimpleMatrix end = SimpleMatrix.random(op.lexOptions.numHid, 1, -0.5, 0.5, rand);
      wordVectors.put(START_WORD, start);
      wordVectors.put(END_WORD, end);
    }
  }


  public int totalParamSize() {
    int totalSize = 0;
    totalSize += numBinaryMatrices * (binaryTransformSize + binaryScoreSize);
    totalSize += numUnaryMatrices * (unaryTransformSize + unaryScoreSize);
    if (TRAIN_WORD_VECTORS) {
      totalSize += wordVectors.size() * op.lexOptions.numHid;
    }
    return totalSize;
  }

  
  public static double[] paramsToVector(double scale, int totalSize, Iterator<SimpleMatrix> ... matrices) {
    double[] theta = new double[totalSize];
    int index = 0;
    for (Iterator<SimpleMatrix> matrixIterator : matrices) {
      while (matrixIterator.hasNext()) {
        SimpleMatrix matrix = matrixIterator.next();
        int numElements = matrix.getNumElements();
        for (int i = 0; i < numElements; ++i) {
          theta[index] = matrix.get(i) * scale;
          ++index;
        }
      }
    }
    if (index != totalSize) {
      throw new AssertionError("Did not entirely fill the theta vector: expected " + totalSize + " used " + index);
    }
    return theta;
  }  
  
  
  public static double[] paramsToVector(int totalSize, Iterator<SimpleMatrix> ... matrices) {
    double[] theta = new double[totalSize];
    int index = 0;
    for (Iterator<SimpleMatrix> matrixIterator : matrices) {
      while (matrixIterator.hasNext()) {
        SimpleMatrix matrix = matrixIterator.next();
        int numElements = matrix.getNumElements();
        //System.out.println(Integer.toString(numElements)); // to know what matrices are
        for (int i = 0; i < numElements; ++i) {
          theta[index] = matrix.get(i);
          ++index;
        }
      }
    }
    if (index != totalSize) {
      throw new AssertionError("Did not entirely fill the theta vector: expected " + totalSize + " used " + index);
    }
    return theta;
  }

  @SuppressWarnings("unchecked")
  public double[] paramsToVector(double scale) {
    int totalSize = totalParamSize();
    if (TRAIN_WORD_VECTORS) {
      return paramsToVector(scale, totalSize, 
                            binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                            binaryScore.valueIterator(), unaryScore.values().iterator(),
                            wordVectors.values().iterator());
    } else {
      return paramsToVector(scale, totalSize, 
                            binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                            binaryScore.valueIterator(), unaryScore.values().iterator());
    }
  }
  
  
  @SuppressWarnings("unchecked")
  public double[] paramsToVector() {
    int totalSize = totalParamSize();
    if (TRAIN_WORD_VECTORS) {
      return paramsToVector(totalSize, 
                            binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                            binaryScore.valueIterator(), unaryScore.values().iterator(),
                            wordVectors.values().iterator());
    } else {
      return paramsToVector(totalSize, 
                            binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                            binaryScore.valueIterator(), unaryScore.values().iterator());
    }
  }

  public static void vectorToParams(double[] theta, Iterator<SimpleMatrix> ... matrices) {
    int index = 0;
    for (Iterator<SimpleMatrix> matrixIterator : matrices) {
      while (matrixIterator.hasNext()) {
        SimpleMatrix matrix = matrixIterator.next();
        int numElements = matrix.getNumElements();
        for (int i = 0; i < numElements; ++i) {
          matrix.set(i, theta[index]);
          ++index;
        }        
      }
    }
    if (index != theta.length) {
      throw new AssertionError("Did not entirely use the theta vector");
    }
  }

  @SuppressWarnings("unchecked")
  public void vectorToParams(double[] theta) {
    if (TRAIN_WORD_VECTORS) {
      vectorToParams(theta, 
                     binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                     binaryScore.valueIterator(), unaryScore.values().iterator(),
                     wordVectors.values().iterator());
    } else {
      vectorToParams(theta, 
                     binaryTransform.valueIterator(), unaryTransform.values().iterator(),
                     binaryScore.valueIterator(), unaryScore.values().iterator());
    }
  }

  public SimpleMatrix getWForNode(Tree node) {
    if (node.children().length == 1) {
      String childLabel = node.children()[0].value();
      String childBasic = basicCategory(childLabel);
      return unaryTransform.get(childBasic);
    } else if (node.children().length == 2) {
      String leftLabel = node.children()[0].value();
      String leftBasic = basicCategory(leftLabel);
      String rightLabel = node.children()[1].value();
      String rightBasic = basicCategory(rightLabel);
      return binaryTransform.get(leftBasic, rightBasic);
    }
    throw new AssertionError("Should only have unary or binary nodes");
  }

  public SimpleMatrix getScoreWForNode(Tree node) {
    if (node.children().length == 1) {
      String childLabel = node.children()[0].value();
      String childBasic = basicCategory(childLabel);
      return unaryScore.get(childBasic);
    } else if (node.children().length == 2) {
      String leftLabel = node.children()[0].value();
      String leftBasic = basicCategory(leftLabel);
      String rightLabel = node.children()[1].value();
      String rightBasic = basicCategory(rightLabel);
      return binaryScore.get(leftBasic, rightBasic);
    }
    throw new AssertionError("Should only have unary or binary nodes");
  }

  public SimpleMatrix getStartWordVector() {
    return wordVectors.get(START_WORD);
  }

  public SimpleMatrix getEndWordVector() {
    return wordVectors.get(END_WORD);
  }

  public SimpleMatrix getWordVector(String word) {
    return wordVectors.get(getVocabWord(word));
  }

  public String getVocabWord(String word) {
    if (op.wordFunction != null) {
      word = op.wordFunction.apply(word);
    }
    if (op.trainOptions.lowercaseWordVectors) {
      word = word.toLowerCase();
    }
    if (wordVectors.containsKey(word)) {
      return word;
    }
    //System.err.println("Unknown word: [" + word + "]");
    if (op.trainOptions.unknownNumberVector && NUMBER_PATTERN.matcher(word).matches()) {
      return UNKNOWN_NUMBER;
    }
    if (op.trainOptions.unknownCapsVector && CAPS_PATTERN.matcher(word).matches()) {
      return UNKNOWN_CAPS;
    }
    if (op.trainOptions.unknownChineseYearVector && CHINESE_YEAR_PATTERN.matcher(word).matches()) {
      return UNKNOWN_CHINESE_YEAR;
    }
    if (op.trainOptions.unknownChineseNumberVector && CHINESE_NUMBER_PATTERN.matcher(word).matches()) {
      return UNKNOWN_CHINESE_NUMBER;
    }
    if (op.trainOptions.unknownChinesePercentVector && CHINESE_PERCENT_PATTERN.matcher(word).matches()) {
      return UNKNOWN_CHINESE_PERCENT;
    }
    if (op.trainOptions.unknownDashedWordVectors) {
      int index = word.lastIndexOf('-');
      if (index >= 0 && index < word.length()) {
        String lastPiece = word.substring(index + 1);
        String wv = getVocabWord(lastPiece);
        if (wv != null) {
          return wv;
        }
      }
    }
    return UNKNOWN_WORD;
  }

  public SimpleMatrix getUnknownWordVector() {
    return wordVectors.get(UNKNOWN_WORD);
  }

  public void printMatrixNames(PrintStream out) {
    out.println("Binary matrices:");
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryTransform) {
      out.println("  " + binary.getFirstKey() + ":" + binary.getSecondKey());
    }
    out.println("Unary matrices:");
    for (String unary : unaryTransform.keySet()) {
      out.println("  " + unary);
    }
  }

  public void printMatrixStats(PrintStream out) {
    System.err.println("Model loaded with " + numUnaryMatrices + " unary and " + numBinaryMatrices + " binary");
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryTransform) {
      out.println("Binary transform " + binary.getFirstKey() + ":" + binary.getSecondKey());
      double normf = binary.getValue().normF();
      out.println("  Total norm " + (normf * normf));
      normf = binary.getValue().extractMatrix(0, op.lexOptions.numHid, 0, op.lexOptions.numHid).normF();
      out.println("  Left norm (" + binary.getFirstKey() + ") " + (normf * normf));
      normf = binary.getValue().extractMatrix(0, op.lexOptions.numHid, op.lexOptions.numHid, op.lexOptions.numHid*2).normF();
      out.println("  Right norm (" + binary.getSecondKey() + ") " + (normf * normf));

    }
  }

  public void printAllMatrices(PrintStream out) {
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryTransform) {
      out.println("Binary transform " + binary.getFirstKey() + ":" + binary.getSecondKey());
      out.println(binary.getValue());
    }
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryScore) {
      out.println("Binary score " + binary.getFirstKey() + ":" + binary.getSecondKey());
      out.println(binary.getValue());
    }
    for (Map.Entry<String, SimpleMatrix> unary : unaryTransform.entrySet()) {
      out.println("Unary transform " + unary.getKey());
      out.println(unary.getValue());
    }
    for (Map.Entry<String, SimpleMatrix> unary : unaryScore.entrySet()) {
      out.println("Unary score " + unary.getKey());
      out.println(unary.getValue());
    }
  }

  
  public int binaryTransformIndex(String leftChild, String rightChild) {
    int pos = 0;
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryTransform) {
      if (binary.getFirstKey().equals(leftChild) && binary.getSecondKey().equals(rightChild)) {
        return pos;
      }
      pos += binary.getValue().getNumElements();
    }
    return -1;
  }

  public int unaryTransformIndex(String child) {
    int pos = binaryTransformSize * numBinaryMatrices;
    for (Map.Entry<String, SimpleMatrix> unary : unaryTransform.entrySet()) {
      if (unary.getKey().equals(child)) {
        return pos;
      }
      pos += unary.getValue().getNumElements();
    }
    return -1;
  }

  public int binaryScoreIndex(String leftChild, String rightChild) {
    int pos = binaryTransformSize * numBinaryMatrices + unaryTransformSize * numUnaryMatrices;
    for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> binary : binaryScore) {
      if (binary.getFirstKey().equals(leftChild) && binary.getSecondKey().equals(rightChild)) {
        return pos;
      }
      pos += binary.getValue().getNumElements();
    }
    return -1;
  }

  public int unaryScoreIndex(String child) {
    int pos = (binaryTransformSize + binaryScoreSize) * numBinaryMatrices + unaryTransformSize * numUnaryMatrices;
    for (Map.Entry<String, SimpleMatrix> unary : unaryScore.entrySet()) {
      if (unary.getKey().equals(child)) {
        return pos;
      }
      pos += unary.getValue().getNumElements();
    }
    return -1;
  }

  public Pair<String, String> indexToBinaryTransform(int pos) {
    if (pos < numBinaryMatrices * binaryTransformSize) {
      for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> entry : binaryTransform) {
        if (binaryTransformSize < pos) {
          pos -= binaryTransformSize;
        } else {
          return Pair.makePair(entry.getFirstKey(), entry.getSecondKey());
        }
      }
    }
    return null;
  }

  public String indexToUnaryTransform(int pos) {
    pos -= numBinaryMatrices * binaryTransformSize;
    if (pos < numUnaryMatrices * unaryTransformSize && pos >= 0) {
      for (Map.Entry<String, SimpleMatrix> entry : unaryTransform.entrySet()) {
        if (unaryTransformSize < pos) {
          pos -= unaryTransformSize;
        } else {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  public Pair<String, String> indexToBinaryScore(int pos) {
    pos -= (numBinaryMatrices * binaryTransformSize + numUnaryMatrices * unaryTransformSize);
    if (pos < numBinaryMatrices * binaryScoreSize && pos >= 0) {
      for (TwoDimensionalMap.Entry<String, String, SimpleMatrix> entry : binaryScore) {
        if (binaryScoreSize < pos) {
          pos -= binaryScoreSize;
        } else {
          return Pair.makePair(entry.getFirstKey(), entry.getSecondKey());
        }
      }
    }
    return null;
  }

  public String indexToUnaryScore(int pos) {
    pos -= (numBinaryMatrices * (binaryTransformSize + binaryScoreSize) + numUnaryMatrices * unaryTransformSize);
    if (pos < numUnaryMatrices * unaryScoreSize && pos >= 0) {
      for (Map.Entry<String, SimpleMatrix> entry : unaryScore.entrySet()) {
        if (unaryScoreSize < pos) {
          pos -= unaryScoreSize;
        } else {
          return entry.getKey();
        }
      }
    }
    return null;
  }



  /**
   * Prints to stdout the type and key for the given location in the parameter stack
   */
  public void printParameterType(int pos, PrintStream out) {
    int originalPos = pos;

    Pair<String, String> binary = indexToBinaryTransform(pos);
    if (binary != null) {
      pos = pos % binaryTransformSize;
      out.println("Entry " + originalPos + " is entry " + pos + " of binary transform " + binary.first() + ":" + binary.second());
      return;
    }

    String unary = indexToUnaryTransform(pos);
    if (unary != null) {
      pos = (pos - numBinaryMatrices * binaryTransformSize) % unaryTransformSize;
      out.println("Entry " + originalPos + " is entry " + pos + " of unary transform " + unary);
      return;
    }

    binary = indexToBinaryScore(pos);
    if (binary != null) {
      pos = (pos - numBinaryMatrices * binaryTransformSize - numUnaryMatrices * unaryTransformSize) % binaryScoreSize;
      out.println("Entry " + originalPos + " is entry " + pos + " of binary score " + binary.first() + ":" + binary.second());
      return;
    }

    unary = indexToUnaryScore(pos);
    if (unary != null) {
      pos = (pos - (numBinaryMatrices * (binaryTransformSize + binaryScoreSize)) - numUnaryMatrices * unaryTransformSize) % unaryScoreSize;
      out.println("Entry " + originalPos + " is entry " + pos + " of unary score " + unary);
      return;
    }

    out.println("Index " + originalPos + " unknown");
  }

  private static final long serialVersionUID = 1;
}
