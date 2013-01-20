// CRFClassifier -- a probabilistic (CRF) sequence model, mainly used for NER.
// Copyright (c) 2002-2008 The Board of Trustees of
// The Leland Stanford Junior University. All Rights Reserved.
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
// For more information, bug reports, fixes, contact:
//    Christopher Manning
//    Dept of Computer Science, Gates 1A
//    Stanford CA 94305-9010
//    USA
//    Support/Questions: java-nlp-user@lists.stanford.edu
//    Licensing: java-nlp-support@lists.stanford.edu

package edu.stanford.nlp.ie.crf;

import edu.stanford.nlp.ie.*;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations.AnswerAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TextAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.math.ArrayMath;
import edu.stanford.nlp.maxent.Convert;
import edu.stanford.nlp.objectbank.ObjectBank;
import edu.stanford.nlp.optimization.*;
import edu.stanford.nlp.sequences.*;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.PaddedList;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.ReflectionLoading;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Timing;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.Quadruple;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Class for Sequence Classification using a Conditional Random Field model.
 * The code has functionality for different document formats, but when
 * using the standard {@link edu.stanford.nlp.sequences.ColumnDocumentReaderAndWriter} for training
 * or testing models, input files are expected to
 * be one token per line with the columns indicating things like the word,
 * POS, chunk, and answer class.  The default for
 * <code>ColumnDocumentReaderAndWriter</code> training data is 3 column input,
 * with the columns containing a word, its POS, and its gold class, but
 * this can be specified via the <code>map</code> property.
 * </p><p>
 * When run on a file with <code>-textFile</code>,
 * the file is assumed to be plain English text (or perhaps simple HTML/XML),
 * and a reasonable attempt is made at English tokenization by
 * {@link PlainTextDocumentReaderAndWriter}.  The class used to read
 * the text can be changed with -plainTextDocumentReaderAndWriter.
 * Extra options can be supplied to the tokenizer using the
 * -tokenizeOptions flag.
 * </p><p>
 * To read from stdin, use the flag -readStdin.  The same
 * reader/writer will be used as for -textFile.
 * </p>
 * <b>Typical command-line usage</b>
 * <p>For running a trained model with a provided serialized classifier on a
 * text file: <p>
 * <code>
 * java -mx500m edu.stanford.nlp.ie.crf.CRFClassifier -loadClassifier
 * conll.ner.gz -textFile samplesentences.txt
 * </code>
 * <p>
 * When specifying all parameters in a properties file (train, test, or
 * runtime):
 * <p>
 * <code>
 * java -mx1g edu.stanford.nlp.ie.crf.CRFClassifier -prop propFile
 * </code>
 * <p>
 * To train and test a simple NER model from the command line:<br>
 * <code>java -mx1000m edu.stanford.nlp.ie.crf.CRFClassifier
 * -trainFile trainFile -testFile testFile -macro &gt; output </code>
 * </p>
 * <p>
 * To train with multiple files: <br>
 * <code>java -mx1000m edu.stanford.nlp.ie.crf.CRFClassifier
 * -trainFileList file1,file2,... -testFile testFile -macro &gt; output</code>
 * </p>
 * <p>
 * To test on multiple files, use the -testFiles option and a comma
 * separated list.
 * </p>
 * Features are defined by a {@link edu.stanford.nlp.sequences.FeatureFactory}.
 * {@link NERFeatureFactory} is used by default, and you should look there for
 * feature templates and properties or flags that will cause certain features to
 * be used when training an NER classifier. There is also a
 * {@link edu.stanford.nlp.wordseg.SighanFeatureFactory}, and various successors
 * such as {@link edu.stanford.nlp.wordseg.ChineseSegmenterFeatureFactory},
 * which are used for Chinese word segmentation. Features are specified either
 * by a Properties file (which is the recommended method) or by flags on the
 * command line. The flags are read into a {@link SeqClassifierFlags} object,
 * which the user need not be concerned with, unless wishing to add new
 * features. </p> CRFClassifier may also be used programmatically. When creating
 * a new instance, you <i>must</i> specify a Properties object. You may then
 * call train methods to train a classifier, or load a classifier. The other way
 * to get a CRFClassifier is to deserialize one via the static
 * {@link CRFClassifier#getClassifier(String)} methods, which return a
 * deserialized classifier. You may then tag (classify the items of) documents
 * using either the assorted <code>classify()</code> or the assorted
 * <code>classify</code> methods in {@link AbstractSequenceClassifier}.
 * Probabilities assigned by the CRF can be interrogated using either the
 * <code>printProbsDocument()</code> or <code>getCliqueTrees()</code> methods.
 *
 * @author Jenny Finkel
 * @author Sonal Gupta (made the class generic)
 */
public class CRFClassifier<IN extends CoreMap> extends AbstractSequenceClassifier<IN> {

  Index<CRFLabel>[] labelIndices;
  /** Parameter weights of the classifier. */
  double[][] weights;
  /** Parameter weights of non-linear CRF */
  double[][] linearWeights;
  double[][] inputLayerWeights4Edge;
  double[][] outputLayerWeights4Edge;
  double[][] inputLayerWeights;
  double[][] outputLayerWeights;
  /** index the features of CRF */
  Index<String> featureIndex;
  /** caches the featureIndex */
  int[] map;
  List<Set<Integer>> featureIndicesSetArray;
  List<List<Integer>> featureIndicesListArray;
  Random random = new Random(2147483647L);
  Index<Integer> nodeFeatureIndicesMap;
  Index<Integer> edgeFeatureIndicesMap;

  /**
   * Name of default serialized classifier resource to look for in a jar file.
   */
  public static final String DEFAULT_CLASSIFIER = "edu/stanford/nlp/models/ner/english.all.3class.distsim.crf.ser.gz";
  private static final boolean VERBOSE = false;

  // List selftraindatums = new ArrayList();

  protected CRFClassifier() {
    super(new SeqClassifierFlags());
  }

  public CRFClassifier(Properties props) {
    super(props);
  }

  public CRFClassifier(SeqClassifierFlags flags) {
    super(flags);
  }

  /**
   * Makes a copy of the crf classifier
   */
  public CRFClassifier(CRFClassifier<IN> crf) {
    super(crf.flags);
    this.windowSize = crf.windowSize;
    this.featureFactory = crf.featureFactory;
    this.pad = crf.pad;
    this.knownLCWords = (crf.knownLCWords != null) ? new HashSet<String>(crf.knownLCWords) : null;
    this.featureIndex = (crf.featureIndex != null) ? new HashIndex<String>(crf.featureIndex.objectsList()) : null;
    if (crf.flags.nonLinearCRF) {
      this.nodeFeatureIndicesMap = (crf.nodeFeatureIndicesMap != null) ? new HashIndex<Integer>(crf.nodeFeatureIndicesMap.objectsList()) : null;
      this.edgeFeatureIndicesMap = (crf.edgeFeatureIndicesMap != null) ? new HashIndex<Integer>(crf.edgeFeatureIndicesMap.objectsList()) : null;
    }
    this.classIndex = (crf.classIndex != null) ? new HashIndex<String>(crf.classIndex.objectsList()) : null;
    if (crf.labelIndices != null) {
      this.labelIndices = new HashIndex[crf.labelIndices.length];
      for (int i = 0; i < crf.labelIndices.length; i++) {
        this.labelIndices[i] = (crf.labelIndices[i] != null) ? new HashIndex<CRFLabel>(crf.labelIndices[i]
            .objectsList()) : null;
      }
    } else {
      this.labelIndices = null;
    }
    int numFeatures = featureIndex != null ? featureIndex.size() : 0;
    weights = new double[numFeatures][];
    for (int i = 0; i < numFeatures; i++) {
      String feature = featureIndex.get(i);
      int index = crf.featureIndex.indexOf(feature);
      weights[i] = new double[crf.weights[index].length];
      System.arraycopy(crf.weights[index], 0, weights[i], 0, weights[i].length);
    }
  }

  /**
   * Returns the total number of weights associated with this classifier.
   *
   * @return number of weights
   */
  public int getNumWeights() {
    if (weights == null) return 0;
    int numWeights = 0;
    for (double[] wts : weights) {
      numWeights += wts.length;
    }
    return numWeights;
  }

  /**
   * Get index of featureType for feature indexed by i. (featureType index is
   * used to index labelIndices to get labels.)
   *
   * @param i
   *          feature index
   * @return index of featureType
   */
  private int getFeatureTypeIndex(int i) {
    return getFeatureTypeIndex(featureIndex.get(i));
  }

  /**
   * Get index of featureType for feature based on the feature string
   * (featureType index used to index labelIndices to get labels)
   *
   * @param feature
   *          feature string
   * @return index of featureType
   */
  private static int getFeatureTypeIndex(String feature) {
    if (feature.endsWith("|C")) {
      return 0;
    } else if (feature.endsWith("|CpC")) {
      return 1;
    } else if (feature.endsWith("|Cp2C")) {
      return 2;
    } else if (feature.endsWith("|Cp3C")) {
      return 3;
    } else if (feature.endsWith("|Cp4C")) {
      return 4;
    } else if (feature.endsWith("|Cp5C")) {
      return 5;
    } else {
      throw new RuntimeException("Unknown feature type " + feature);
    }
  }

  /**
   * Scales the weights of this crfclassifier by the specified weight
   *
   * @param scale
   */
  public void scaleWeights(double scale) {
    for (int i = 0; i < weights.length; i++) {
      for (int j = 0; j < weights[i].length; j++) {
        weights[i][j] *= scale;
      }
    }
  }

  /**
   * Combines weights from another crf (scaled by weight) into this crf's
   * weights (assumes that this crf's indices have already been updated to
   * include features/labels from the other crf)
   *
   * @param crf
   *          Other crf whose weights to combine into this crf
   * @param weight
   *          amount to scale the other crf's weights by
   */
  private void combineWeights(CRFClassifier<IN> crf, double weight) {
    int numFeatures = featureIndex.size();
    int oldNumFeatures = weights.length;

    // Create a map of other crf labels to this crf labels
    Map<CRFLabel, CRFLabel> crfLabelMap = new HashMap<CRFLabel, CRFLabel>();
    for (int i = 0; i < crf.labelIndices.length; i++) {
      for (int j = 0; j < crf.labelIndices[i].size(); j++) {
        CRFLabel labels = crf.labelIndices[i].get(j);
        int[] newLabelIndices = new int[i + 1];
        for (int ci = 0; ci <= i; ci++) {
          String classLabel = crf.classIndex.get(labels.getLabel()[ci]);
          newLabelIndices[ci] = this.classIndex.indexOf(classLabel);
        }
        CRFLabel newLabels = new CRFLabel(newLabelIndices);
        crfLabelMap.put(labels, newLabels);
        int k = this.labelIndices[i].indexOf(newLabels); // the indexing is needed, even when not printed out!
        // System.err.println("LabelIndices " + i + " " + labels + ": " + j +
        // " mapped to " + k);
      }
    }

    // Create map of featureIndex to featureTypeIndex
    map = new int[numFeatures];
    for (int i = 0; i < numFeatures; i++) {
      map[i] = getFeatureTypeIndex(i);
    }

    // Create new weights
    double[][] newWeights = new double[numFeatures][];
    for (int i = 0; i < numFeatures; i++) {
      int length = labelIndices[map[i]].size();
      newWeights[i] = new double[length];
      if (i < oldNumFeatures) {
        assert (length >= weights[i].length);
        System.arraycopy(weights[i], 0, newWeights[i], 0, weights[i].length);
      }
    }
    weights = newWeights;

    // Get original weight indices from other crf and weight them in
    // depending on the type of the feature, different number of weights is
    // associated with it
    for (int i = 0; i < crf.weights.length; i++) {
      String feature = crf.featureIndex.get(i);
      int newIndex = featureIndex.indexOf(feature);
      // Check weights are okay dimension
      if (weights[newIndex].length < crf.weights[i].length) {
        throw new RuntimeException("Incompatible CRFClassifier: weight length mismatch for feature " + newIndex + ": "
            + featureIndex.get(newIndex) + " (also feature " + i + ": " + crf.featureIndex.get(i) + ") " + ", len1="
            + weights[newIndex].length + ", len2=" + crf.weights[i].length);
      }
      int featureTypeIndex = map[newIndex];
      for (int j = 0; j < crf.weights[i].length; j++) {
        CRFLabel labels = crf.labelIndices[featureTypeIndex].get(j);
        CRFLabel newLabels = crfLabelMap.get(labels);
        int k = this.labelIndices[featureTypeIndex].indexOf(newLabels);
        weights[newIndex][k] += crf.weights[i][j] * weight;
      }
    }
  }

  /**
   * Combines weighted crf with this crf
   *
   * @param crf
   * @param weight
   */
  public void combine(CRFClassifier<IN> crf, double weight) {
    Timing timer = new Timing();

    // Check the CRFClassifiers are compatible
    if (!this.pad.equals(crf.pad)) {
      throw new RuntimeException("Incompatible CRFClassifier: pad does not match");
    }
    if (this.windowSize != crf.windowSize) {
      throw new RuntimeException("Incompatible CRFClassifier: windowSize does not match");
    }
    if (this.labelIndices.length != crf.labelIndices.length) {
      // Should match since this should be same as the windowSize
      throw new RuntimeException("Incompatible CRFClassifier: labelIndices length does not match");
    }
    this.classIndex.addAll(crf.classIndex.objectsList());

    // Combine weights of the other classifier with this classifier,
    // weighing the other classifier's weights by weight
    // First merge the feature indicies
    int oldNumFeatures1 = this.featureIndex.size();
    int oldNumFeatures2 = crf.featureIndex.size();
    int oldNumWeights1 = this.getNumWeights();
    int oldNumWeights2 = crf.getNumWeights();
    this.featureIndex.addAll(crf.featureIndex.objectsList());
    this.knownLCWords.addAll(crf.knownLCWords);
    assert (weights.length == oldNumFeatures1);

    // Combine weights of this classifier with other classifier
    for (int i = 0; i < labelIndices.length; i++) {
      this.labelIndices[i].addAll(crf.labelIndices[i].objectsList());
    }
    System.err.println("Combining weights: will automatically match labelIndices");
    combineWeights(crf, weight);

    int numFeatures = featureIndex.size();
    int numWeights = getNumWeights();
    long elapsedMs = timer.stop();
    System.err.println("numFeatures: orig1=" + oldNumFeatures1 + ", orig2=" + oldNumFeatures2 + ", combined="
        + numFeatures);
    System.err
        .println("numWeights: orig1=" + oldNumWeights1 + ", orig2=" + oldNumWeights2 + ", combined=" + numWeights);
    System.err.println("Time to combine CRFClassifier: " + Timing.toSecondsString(elapsedMs) + " seconds");
  }

  public void dropFeaturesBelowThreshold(double threshold) {
    Index<String> newFeatureIndex = new HashIndex<String>();
    for (int i = 0; i < weights.length; i++) {
      double smallest = weights[i][0];
      double biggest = weights[i][0];
      for (int j = 1; j < weights[i].length; j++) {
        if (weights[i][j] > biggest) {
          biggest = weights[i][j];
        }
        if (weights[i][j] < smallest) {
          smallest = weights[i][j];
        }
        if (biggest - smallest > threshold) {
          newFeatureIndex.add(featureIndex.get(i));
          break;
        }
      }
    }

    int[] newMap = new int[newFeatureIndex.size()];
    for (int i = 0; i < newMap.length; i++) {
      int index = featureIndex.indexOf(newFeatureIndex.get(i));
      newMap[i] = map[index];
    }
    map = newMap;
    featureIndex = newFeatureIndex;
  }

  /**
   * Convert a document List into arrays storing the data features and labels.
   * This is used at test time.
   *
   * @param document Testing documents
   * @return A Pair, where the first element is an int[][][] representing the
   *         data and the second element is an int[] representing the labels
   */
  public Pair<int[][][], int[]> documentToDataAndLabels(List<IN> document) {
    return documentToDataAndLabels(document, false);
  }

  /**
   * Convert a document List into arrays storing the data features and labels.
   * This is used at both training and test time.
   *
   * @param document Training documents
   * @return A Pair, where the first element is an int[][][] representing the
   *         data and the second element is an int[] representing the labels
   */
  public Pair<int[][][], int[]> documentToDataAndLabels(List<IN> document,
                                                        boolean trainTime) {
    boolean droppedFeature = false; // todo: remove me
    int docSize = document.size();
    // first index is position in the document also the index of the
    // clique/factor table
    // second index is the number of elements in the clique/window these
    // features are for (starting with last element)
    // third index is position of the feature in the array that holds them.
    // An element in data[j][k][m] is the index of the mth feature occurring in
    // position k of the jth clique
    int[][][] data = new int[docSize][windowSize][];
    // index is the position in the document.
    // element in labels[j] is the index of the correct label (if it exists) at
    // position j of document
    int[] labels = new int[docSize];

    if (flags.useReverse) {
      Collections.reverse(document);
    }

    // System.err.println("docSize:"+docSize);
    for (int j = 0; j < docSize; j++) {
      CRFDatum<List<String>, CRFLabel> d = makeDatum(document, j, featureFactory);

      List<List<String>> features = d.asFeatures();
      for (int k = 0, fSize = features.size(); k < fSize; k++) {
        Collection<String> cliqueFeatures = features.get(k);
        data[j][k] = new int[cliqueFeatures.size()];
        int m = 0;
        for (String feature : cliqueFeatures) {
          if (trainTime && flags.inputDropOut != 0.0) {
            double rand = Math.random();
            if (rand < flags.inputDropOut) {
              if ( ! droppedFeature) {
                System.err.printf("Dropped feature %s%n", feature);
                droppedFeature = true;
              }
              continue;
            }
          }

          int index = featureIndex.indexOf(feature);
          if (index >= 0) {
            data[j][k][m] = index;
            m++;
          } else {
            // this is where we end up when we do feature threshold cutoffs
          }
        }

        if (m < data[j][k].length) {
          int[] f = new int[m];
          System.arraycopy(data[j][k], 0, f, 0, m);
          data[j][k] = f;
        }
      }

      IN wi = document.get(j);
      labels[j] = classIndex.indexOf(wi.get(AnswerAnnotation.class));
    }

    if (flags.useReverse) {
      Collections.reverse(document);
    }

    // System.err.println("numClasses: "+classIndex.size()+" "+classIndex);
    // System.err.println("numDocuments: 1");
    // System.err.println("numDatums: "+data.length);
    // System.err.println("numFeatures: "+featureIndex.size());

    if (flags.nonLinearCRF) {
      data = transformDocData(data);
    }

    return new Pair<int[][][], int[]>(data, labels);
  }

  private int[][][] transformDocData(int[][][] docData) {
    int[][][] transData = new int[docData.length][][];
    for (int i = 0; i < docData.length; i++) {
      transData[i] = new int[docData[i].length][];
      for (int j = 0; j < docData[i].length; j++) {
        int[] cliqueFeatures = docData[i][j];
        transData[i][j] = new int[cliqueFeatures.length];
        for (int n = 0; n < cliqueFeatures.length; n++) {
          int transFeatureIndex = -1;
          if (j == 0) {
            transFeatureIndex = nodeFeatureIndicesMap.indexOf(cliqueFeatures[n]);
          } else {
            transFeatureIndex = edgeFeatureIndicesMap.indexOf(cliqueFeatures[n]);
          }
          transData[i][j][n] = transFeatureIndex;
        }
      }
    }
    return transData;
  }

  public void printLabelInformation(String testFile, DocumentReaderAndWriter<IN> readerAndWriter) throws Exception {
    ObjectBank<List<IN>> documents = makeObjectBankFromFile(testFile, readerAndWriter);
    for (List<IN> document : documents) {
      printLabelValue(document);
    }
  }

  public void printLabelValue(List<IN> document) {

    if (flags.useReverse) {
      Collections.reverse(document);
    }

    NumberFormat nf = new DecimalFormat();

    List<String> classes = new ArrayList<String>();
    for (int i = 0; i < classIndex.size(); i++) {
      classes.add(classIndex.get(i));
    }
    String[] columnHeaders = classes.toArray(new String[classes.size()]);

    // System.err.println("docSize:"+docSize);
    for (int j = 0; j < document.size(); j++) {

      System.out.println("--== " + document.get(j).get(TextAnnotation.class) + " ==--");

      List<String[]> lines = new ArrayList<String[]>();
      List<String> rowHeaders = new ArrayList<String>();
      List<String> line = new ArrayList<String>();

      for (int p = 0; p < labelIndices.length; p++) {
        if (j + p >= document.size()) {
          continue;
        }
        CRFDatum<List<String>, CRFLabel> d = makeDatum(document, j + p, featureFactory);

        List<List<String>> features = d.asFeatures();
        for (int k = p, fSize = features.size(); k < fSize; k++) {
          Collection<String> cliqueFeatures = features.get(k);
          for (String feature : cliqueFeatures) {
            int index = featureIndex.indexOf(feature);
            if (index >= 0) {
              // line.add(feature+"["+(-p)+"]");
              rowHeaders.add(feature + '[' + (-p) + ']');
              double[] values = new double[labelIndices[0].size()];
              for (CRFLabel label : labelIndices[k]) {
                int[] l = label.getLabel();
                double v = weights[index][labelIndices[k].indexOf(label)];
                values[l[l.length - 1 - p]] += v;
              }
              for (double value : values) {
                line.add(nf.format(value));
              }
              lines.add(line.toArray(new String[line.size()]));
              line = new ArrayList<String>();
            }
          }
        }
        // lines.add(Collections.<String>emptyList());
        System.out.println(StringUtils.makeAsciiTable(lines.toArray(new String[lines.size()][0]), rowHeaders
            .toArray(new String[rowHeaders.size()]), columnHeaders, 0, 1, true));
        System.out.println();
      }
      // System.err.println(edu.stanford.nlp.util.StringUtils.join(lines,"\n"));
    }

    if (flags.useReverse) {
      Collections.reverse(document);
    }
  }

  /**
   * Convert an ObjectBank to arrays of data features and labels.
   * This version is used at training time.
   *
   * @return A Pair, where the first element is an int[][][][] representing the
   *         data and the second element is an int[][] representing the labels.
   */
  public Pair<int[][][][], int[][]> documentsToDataAndLabels(Collection<List<IN>> documents) {

    // first index is the number of the document
    // second index is position in the document also the index of the
    // clique/factor table
    // third index is the number of elements in the clique/window these features
    // are for (starting with last element)
    // fourth index is position of the feature in the array that holds them
    // element in data[i][j][k][m] is the index of the mth feature occurring in
    // position k of the jth clique of the ith document
    // int[][][][] data = new int[documentsSize][][][];
    List<int[][][]> data = new ArrayList<int[][][]>();

    // first index is the number of the document
    // second index is the position in the document
    // element in labels[i][j] is the index of the correct label (if it exists)
    // at position j in document i
    // int[][] labels = new int[documentsSize][];
    List<int[]> labels = new ArrayList<int[]>();

    int numDatums = 0;

    for (List<IN> doc : documents) {
      Pair<int[][][], int[]> docPair = documentToDataAndLabels(doc, true);
      data.add(docPair.first());
      labels.add(docPair.second());
      numDatums += doc.size();
    }

    System.err.println("numClasses: " + classIndex.size() + ' ' + classIndex);
    System.err.println("numDocuments: " + data.size());
    System.err.println("numDatums: " + numDatums);
    System.err.println("numFeatures: " + featureIndex.size());
    printFeatures();

    return new Pair<int[][][][], int[][]>(
            data.toArray(new int[data.size()][][][]),
            labels.toArray(new int[labels.size()][]));
  }

  /**
   * Convert an ObjectBank to corresponding collection of data features and
   * labels. This version is used at test time.
   *
   * @return A List of pairs, one for each document, where the first element is
   *         an int[][][] representing the data and the second element is an
   *         int[] representing the labels.
   */
  public List<Pair<int[][][], int[]>> documentsToDataAndLabelsList(Collection<List<IN>> documents) {
    int numDatums = 0;

    List<Pair<int[][][], int[]>> docList = new ArrayList<Pair<int[][][], int[]>>();
    for (List<IN> doc : documents) {
      Pair<int[][][], int[]> docPair = documentToDataAndLabels(doc);
      docList.add(docPair);
      numDatums += doc.size();
    }

    System.err.println("numClasses: " + classIndex.size() + ' ' + classIndex);
    System.err.println("numDocuments: " + docList.size());
    System.err.println("numDatums: " + numDatums);
    System.err.println("numFeatures: " + featureIndex.size());
    return docList;
  }

  protected void printFeatures() {
    if (flags.printFeatures == null) {
      return;
    }
    try {
      String enc = flags.inputEncoding;
      if (flags.inputEncoding == null) {
        System.err.println("flags.inputEncoding doesn't exist, Use UTF-8 as default");
        enc = "UTF-8";
      }

      PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream("features-" + flags.printFeatures
          + ".txt"), enc), true);
      for (String feat : featureIndex) {
        pw.println(feat);
      }
      pw.close();
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }

  /**
   * This routine builds the <code>labelIndices</code> which give the
   * empirically legal label sequences (of length (order) at most
   * <code>windowSize</code>) and the <code>classIndex</code>, which indexes
   * known answer classes.
   *
   * @param ob The training data: Read from an ObjectBank, each item in it is a
   *          {@code List<CoreLabel>}.
   */
  protected void makeAnswerArraysAndTagIndex(Collection<List<IN>> ob) {

    HashSet<String>[] featureIndices = new HashSet[windowSize];
    for (int i = 0; i < windowSize; i++) {
      featureIndices[i] = new HashSet<String>();
    }

    labelIndices = new HashIndex[windowSize];
    for (int i = 0; i < labelIndices.length; i++) {
      labelIndices[i] = new HashIndex<CRFLabel>();
    }

    Index<CRFLabel> labelIndex = labelIndices[windowSize - 1];

    classIndex = new HashIndex<String>();
    // classIndex.add("O");
    classIndex.add(flags.backgroundSymbol);

    HashSet<String>[] seenBackgroundFeatures = new HashSet[2];
    seenBackgroundFeatures[0] = new HashSet<String>();
    seenBackgroundFeatures[1] = new HashSet<String>();


    int wordCount = 0;

    for (List<IN> doc : ob) {

      if (flags.useReverse) {
        Collections.reverse(doc);
      }

      int docSize = doc.size();
      // create the full set of labels in classIndex
      // note: update to use addAll later
      for (int j = 0; j < docSize; j++) {
        wordCount++;
        String ans = doc.get(j).get(AnswerAnnotation.class);
        if (ans == null || ans.equals("")) {
          throw new IllegalArgumentException("Word " + wordCount + " (\"" + doc.get(j).get(TextAnnotation.class) + "\") has a blank answer");
        }
        classIndex.add(ans);
      }

      for (int j = 0; j < docSize; j++) {

        CRFDatum<List<String>, CRFLabel> d = makeDatum(doc, j, featureFactory);
        labelIndex.add(d.label());

        List<List<String>> features = d.asFeatures();
        for (int k = 0, fsize = features.size(); k < fsize; k++) {
          Collection<String> cliqueFeatures = features.get(k);
          if (k < 2 && flags.removeBackgroundSingletonFeatures) {
            String ans = doc.get(j).get(AnswerAnnotation.class);
            boolean background = ans.equals(flags.backgroundSymbol);
            if (k == 1 && j > 0 && background) {
              ans = doc.get(j - 1).get(AnswerAnnotation.class);
              background = ans.equals(flags.backgroundSymbol);
            }
            if (background) {
              for (String f : cliqueFeatures) {
                if (!featureIndices[k].contains(f)) {
                  if (seenBackgroundFeatures[k].contains(f)) {
                    seenBackgroundFeatures[k].remove(f);
                    featureIndices[k].add(f);
                  } else {
                    seenBackgroundFeatures[k].add(f);
                  }
                }
              }
            } else {
              seenBackgroundFeatures[k].removeAll(cliqueFeatures);
              featureIndices[k].addAll(cliqueFeatures);
            }
          } else {
            featureIndices[k].addAll(cliqueFeatures);
          }
        }
      }

      if (flags.useReverse) {
        Collections.reverse(doc);
      }
    }

    // String[] fs = new String[featureIndices[0].size()];
    // for (Iterator iter = featureIndices[0].iterator(); iter.hasNext(); ) {
    // System.err.println(iter.next());
    // }

    int numFeatures = 0;
    for (int i = 0; i < windowSize; i++) {
      numFeatures += featureIndices[i].size();
    }

    featureIndex = new HashIndex<String>();
    map = new int[numFeatures];
    for (int i = 0; i < windowSize; i++) {
      Index<Integer> featureIndexMap = new HashIndex<Integer>();
      
      featureIndex.addAll(featureIndices[i]);
      for (String str : featureIndices[i]) {
        int index = featureIndex.indexOf(str);
        map[index] = i;
        featureIndexMap.add(index);
      }
      if (i == 0) {
        nodeFeatureIndicesMap = featureIndexMap;
      } else {
        edgeFeatureIndicesMap = featureIndexMap;
      }
    }

    if (flags.useObservedSequencesOnly) {
      for (int i = 0, liSize = labelIndex.size(); i < liSize; i++) {
        CRFLabel label = labelIndex.get(i);
        for (int j = windowSize - 2; j >= 0; j--) {
          label = label.getOneSmallerLabel();
          labelIndices[j].add(label);
        }
      }
    } else {
      for (int i = 0; i < labelIndices.length; i++) {
        labelIndices[i] = allLabels(i + 1, classIndex);
      }
    }

    if (VERBOSE) {
      for (int i = 0, fiSize = featureIndex.size(); i < fiSize; i++) {
        System.out.println(i + ": " + featureIndex.get(i));
      }
    }
  }

  protected static Index<CRFLabel> allLabels(int window, Index<String> classIndex) {
    int[] label = new int[window];
    // cdm july 2005: below array initialization isn't necessary: JLS (3rd ed.)
    // 4.12.5
    // Arrays.fill(label, 0);
    int numClasses = classIndex.size();
    Index<CRFLabel> labelIndex = new HashIndex<CRFLabel>();
    OUTER: while (true) {
      CRFLabel l = new CRFLabel(label);
      labelIndex.add(l);
      int[] label1 = new int[window];
      System.arraycopy(label, 0, label1, 0, label.length);
      label = label1;
      for (int j = 0; j < label.length; j++) {
        label[j]++;
        if (label[j] >= numClasses) {
          label[j] = 0;
          if (j == label.length - 1) {
            break OUTER;
          }
        } else {
          break;
        }
      }
    }
    return labelIndex;
  }

  /**
   * Makes a CRFDatum by producing features and a label from input data at a
   * specific position, using the provided factory.
   *
   * @param info The input data
   * @param loc The position to build a datum at
   * @param featureFactory The FeatureFactory to use to extract features
   * @return The constructed CRFDatum
   */
  public CRFDatum<List<String>, CRFLabel> makeDatum(List<IN> info, int loc,
      edu.stanford.nlp.sequences.FeatureFactory<IN> featureFactory) {
    // pad.set(AnswerAnnotation.class, flags.backgroundSymbol); // cdm: isn't this unnecessary, as this is how it's initialized in AbstractSequenceClassifier.reinit?
    PaddedList<IN> pInfo = new PaddedList<IN>(info, pad);

    ArrayList<List<String>> features = new ArrayList<List<String>>();

    // for (int i = 0; i < windowSize; i++) {
    // List featuresC = new ArrayList();
    // for (int j = 0; j < FeatureFactory.win[i].length; j++) {
    // featuresC.addAll(featureFactory.features(info, loc,
    // FeatureFactory.win[i][j]));
    // }
    // features.add(featuresC);
    // }

    // todo [cdm Aug 2012]: Since getCliques returns all cliques within its bounds, can't the for loop here be eliminated? But my first attempt to removed failed to produce identical results....
    Collection<Clique> done = new HashSet<Clique>();
    for (int i = 0; i < windowSize; i++) {
      List<String> featuresC = new ArrayList<String>();
      List<Clique> windowCliques = FeatureFactory.getCliques(i, 0);
      windowCliques.removeAll(done);
      done.addAll(windowCliques);
      for (Clique c : windowCliques) {
        featuresC.addAll(featureFactory.getCliqueFeatures(pInfo, loc, c)); //todo useless copy because of typing reasons
      }
    // [This is the version that didn't work.]
    // List<Clique> windowCliques = FeatureFactory.getCliques(windowSize - 1, 0); // -1 as <= in getCliques()
    // List<List<String>> features = new ArrayList<List<String>>(windowCliques.size());
    // for (Clique c : windowCliques) {
    //   List<String> featuresC = new ArrayList<String>(featureFactory.getCliqueFeatures(pInfo, loc, c)); //todo useless copy because of typing reasons
      features.add(featuresC);
    }

    int[] labels = new int[windowSize];

    for (int i = 0; i < windowSize; i++) {
      String answer = pInfo.get(loc + i - windowSize + 1).get(AnswerAnnotation.class);
      labels[i] = classIndex.indexOf(answer);
    }

    printFeatureLists(pInfo.get(loc), features);

    CRFDatum<List<String>, CRFLabel> d = new CRFDatum<List<String>, CRFLabel>(features, new CRFLabel(labels));
    // System.err.println(d);
    return d;
  }

  public static class TestSequenceModel implements SequenceModel {

    private final int window;
    private final int numClasses;
    // private final FactorTable[] factorTables;
    private final CRFCliqueTree cliqueTree;
    private final int[] tags;
    private final int[] backgroundTag;

    // public Scorer(FactorTable[] factorTables) {
    public TestSequenceModel(CRFCliqueTree cliqueTree) {
      // this.factorTables = factorTables;
      this.cliqueTree = cliqueTree;
      // this.window = factorTables[0].windowSize();
      this.window = cliqueTree.window();
      // this.numClasses = factorTables[0].numClasses();
      this.numClasses = cliqueTree.getNumClasses();
      tags = new int[numClasses];
      for (int i = 0; i < tags.length; i++) {
        tags[i] = i;
      }
      backgroundTag = new int[] { cliqueTree.backgroundIndex() };
    }

    @Override
    public int length() {
      return cliqueTree.length();
    }

    @Override
    public int leftWindow() {
      return window - 1;
    }

    @Override
    public int rightWindow() {
      return 0;
    }

    @Override
    public int[] getPossibleValues(int pos) {
      if (pos < window - 1) {
        return backgroundTag;
      }
      return tags;
    }

    /**
     * Return the score of the proposed tags for position given.
     * @param tags is an array indicating the assignment of labels to score.
     * @param pos is the position to return a score for.
     */
    @Override
    public double scoreOf(int[] tags, int pos) {
      int[] previous = new int[window - 1];
      int realPos = pos - window + 1;
      for (int i = 0; i < window - 1; i++) {
        previous[i] = tags[realPos + i];
      }
      return cliqueTree.condLogProbGivenPrevious(realPos, tags[pos], previous);
    }

    @Override
    public double[] scoresOf(int[] tags, int pos) {
      int realPos = pos - window + 1;
      double[] scores = new double[numClasses];
      int[] previous = new int[window - 1];
      for (int i = 0; i < window - 1; i++) {
        previous[i] = tags[realPos + i];
      }
      for (int i = 0; i < numClasses; i++) {
        scores[i] = cliqueTree.condLogProbGivenPrevious(realPos, i, previous);
      }
      return scores;
    }

    @Override
    public double scoreOf(int[] sequence) {
      throw new UnsupportedOperationException();
    }

  } // end class TestSequenceModel

  @Override
  public List<IN> classify(List<IN> document) {
    if (flags.doGibbs) {
      try {
        return classifyGibbs(document);
      } catch (Exception e) {
        System.err.println("Error running testGibbs inference!");
        e.printStackTrace();
        return null;
      }
    } else if (flags.crfType.equalsIgnoreCase("maxent")) {
      return classifyMaxEnt(document);
    } else {
      throw new RuntimeException("Unsupported inference type: " + flags.crfType);
    }
  }

  private List<IN> classify(List<IN> document, Pair<int[][][], int[]> documentDataAndLabels) {
    if (flags.doGibbs) {
      try {
        return classifyGibbs(document, documentDataAndLabels);
      } catch (Exception e) {
        System.err.println("Error running testGibbs inference!");
        e.printStackTrace();
        return null;
      }
    } else if (flags.crfType.equalsIgnoreCase("maxent")) {
      return classifyMaxEnt(document, documentDataAndLabels);
    } else {
      throw new RuntimeException("Unsupported inference type: " + flags.crfType);
    }
  }

  public void classifyAndWriteAnswers(Collection<List<IN>> documents,
                                      List<Pair<int[][][], int[]>> documentDataAndLabels, 
                                      PrintWriter printWriter,
                                      DocumentReaderAndWriter<IN> readerAndWriter) throws IOException {
    Timing timer = new Timing();

    Counter<String> entityTP = new ClassicCounter<String>();
    Counter<String> entityFP = new ClassicCounter<String>();
    Counter<String> entityFN = new ClassicCounter<String>();
    boolean resultsCounted = true;

    int numWords = 0;
    int numDocs = 0;
    for (List<IN> doc : documents) {
      classify(doc, documentDataAndLabels.get(numDocs));
      numWords += doc.size();
      writeAnswers(doc, printWriter, readerAndWriter);
      resultsCounted = resultsCounted && countResults(doc, entityTP, entityFP, entityFN);
      numDocs++;
    }
    long millis = timer.stop();
    double wordspersec = numWords / (((double) millis) / 1000);
    NumberFormat nf = new DecimalFormat("0.00"); // easier way!
    System.err.println(StringUtils.getShortClassName(this) + " tagged " + numWords + " words in " + numDocs
        + " documents at " + nf.format(wordspersec) + " words per second.");
    if (resultsCounted) {
      printResults(entityTP, entityFP, entityFN);
    }
  }

  @Override
  public SequenceModel getSequenceModel(List<IN> doc) {
    Pair<int[][][], int[]> p = documentToDataAndLabels(doc);
    return getSequenceModel(doc, p);
  }

  public SequenceModel getSequenceModel(List<IN> doc, Pair<int[][][], int[]> documentDataAndLabels) {
    Pair<int[][][], int[]> p = documentDataAndLabels;
    return new TestSequenceModel(getCliqueTree(p));
  }

  private CliquePotentialFunction getCliquePotentialFunction() {
    if (flags.nonLinearCRF) {
      if (flags.secondOrderNonLinear) 
        return new NonLinearSecondOrderCliquePotentialFunction(inputLayerWeights4Edge, outputLayerWeights4Edge, inputLayerWeights, outputLayerWeights, flags); 
      else
        return new NonLinearCliquePotentialFunction(linearWeights, inputLayerWeights, outputLayerWeights, flags); 
    } else {
      return new LinearCliquePotentialFunction(weights);
    }
  }

  /**
   * Do standard sequence inference, using either Viterbi or Beam inference
   * depending on the value of <code>flags.inferenceType</code>.
   *
   * @param document
   *          Document to classify. Classification happens in place. This
   *          document is modified.
   * @return The classified document
   */
  public List<IN> classifyMaxEnt(List<IN> document) {
    if (document.isEmpty()) {
      return document;
    }

    SequenceModel model = getSequenceModel(document);
    return classifyMaxEnt(document, model);
  }

  private List<IN> classifyMaxEnt(List<IN> document, Pair<int[][][], int[]> documentDataAndLabels) {
    if (document.isEmpty()) {
      return document;
    }
    SequenceModel model = getSequenceModel(document, documentDataAndLabels);
    return classifyMaxEnt(document, model);
  }

  private List<IN> classifyMaxEnt(List<IN> document, SequenceModel model) {
    if (document.isEmpty()) {
      return document;
    }

    if (flags.inferenceType == null) {
      flags.inferenceType = "Viterbi";
    }

    BestSequenceFinder tagInference;
    if (flags.inferenceType.equalsIgnoreCase("Viterbi")) {
      tagInference = new ExactBestSequenceFinder();
    } else if (flags.inferenceType.equalsIgnoreCase("Beam")) {
      tagInference = new BeamBestSequenceFinder(flags.beamSize);
    } else {
      throw new RuntimeException("Unknown inference type: " + flags.inferenceType + ". Your options are Viterbi|Beam.");
    }

    int[] bestSequence = tagInference.bestSequence(model);

    if (flags.useReverse) {
      Collections.reverse(document);
    }
    for (int j = 0, docSize = document.size(); j < docSize; j++) {
      IN wi = document.get(j);
      String guess = classIndex.get(bestSequence[j + windowSize - 1]);
      wi.set(AnswerAnnotation.class, guess);
    }
    if (flags.useReverse) {
      Collections.reverse(document);
    }
    return document;
  }

  public List<IN> classifyGibbs(List<IN> document) throws ClassNotFoundException, SecurityException,
      NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException,
      InvocationTargetException {
    Pair<int[][][], int[]> p = documentToDataAndLabels(document);
    return classifyGibbs(document, p);
  }

  public List<IN> classifyGibbs(List<IN> document, Pair<int[][][], int[]> documentDataAndLabels)
      throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
      InstantiationException, IllegalAccessException, InvocationTargetException {
    // System.err.println("Testing using Gibbs sampling.");
    Pair<int[][][], int[]> p = documentDataAndLabels;
    List<IN> newDocument = document; // reversed if necessary
    if (flags.useReverse) {
      Collections.reverse(document);
      newDocument = new ArrayList<IN>(document);
      Collections.reverse(document);
    }

    CRFCliqueTree<? extends CharSequence> cliqueTree = getCliqueTree(p);

    SequenceModel model = cliqueTree;
    SequenceListener listener = cliqueTree;

    SequenceModel priorModel = null;
    SequenceListener priorListener = null;

    if (flags.useNERPrior) {
      EntityCachingAbstractSequencePrior<IN> prior = new EmpiricalNERPrior<IN>(flags.backgroundSymbol, classIndex,
          newDocument);
      // SamplingNERPrior prior = new SamplingNERPrior(flags.backgroundSymbol,
      // classIndex, newDocument);
      priorModel = prior;
      priorListener = prior;
    } else if (flags.useAcqPrior) {
      EntityCachingAbstractSequencePrior<IN> prior = new AcquisitionsPrior<IN>(flags.backgroundSymbol, classIndex,
          newDocument);
      priorModel = prior;
      priorListener = prior;
    } else if (flags.useSemPrior) {
      EntityCachingAbstractSequencePrior<IN> prior = new SeminarsPrior<IN>(flags.backgroundSymbol, classIndex,
          newDocument);
      priorModel = prior;
      priorListener = prior;
    } else if (flags.useUniformPrior) {
      // System.err.println("Using uniform prior!");
      UniformPrior<IN> uniPrior = new UniformPrior<IN>(flags.backgroundSymbol, classIndex, newDocument);
      priorModel = uniPrior;
      priorListener = uniPrior;
    } else {
      throw new RuntimeException("no prior specified");
    }

    model = new FactoredSequenceModel(model, priorModel);
    listener = new FactoredSequenceListener(listener, priorListener);

    SequenceGibbsSampler sampler = new SequenceGibbsSampler(0, 0, listener);
    int[] sequence = new int[cliqueTree.length()];

    if (flags.initViterbi) {
      TestSequenceModel testSequenceModel = new TestSequenceModel(cliqueTree);
      ExactBestSequenceFinder tagInference = new ExactBestSequenceFinder();
      int[] bestSequence = tagInference.bestSequence(testSequenceModel);
      System.arraycopy(bestSequence, windowSize - 1, sequence, 0, sequence.length);
    } else {
      int[] initialSequence = SequenceGibbsSampler.getRandomSequence(model);
      System.arraycopy(initialSequence, 0, sequence, 0, sequence.length);
    }

    sampler.verbose = 0;

    if (flags.annealingType.equalsIgnoreCase("linear")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getLinearSchedule(1.0, flags.numSamples),
          sequence);
    } else if (flags.annealingType.equalsIgnoreCase("exp") || flags.annealingType.equalsIgnoreCase("exponential")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getExponentialSchedule(1.0, flags.annealingRate,
          flags.numSamples), sequence);
    } else {
      throw new RuntimeException("No annealing type specified");
    }

    // System.err.println(ArrayMath.toString(sequence));

    if (flags.useReverse) {
      Collections.reverse(document);
    }

    for (int j = 0, dsize = newDocument.size(); j < dsize; j++) {
      IN wi = document.get(j);
      if (wi == null) throw new RuntimeException("");
      if (classIndex == null) throw new RuntimeException("");
      wi.set(AnswerAnnotation.class, classIndex.get(sequence[j]));
    }

    if (flags.useReverse) {
      Collections.reverse(document);
    }

    return document;
  }

  /**
   *
   * @param sentence
   * @param priorModels
   *          an array of prior models
   * @param priorListeners
   *          an array of prior listeners
   * @param modelWts
   *          an array of model weights: IMPORTANT: this includes the weight of
   *          CRF classifier as well at position 0, and therefore is longer than
   *          priorListeners/priorModels array by 1.
   * @return A list of INs with
   * @throws ClassNotFoundException
   * @throws SecurityException
   * @throws NoSuchMethodException
   * @throws IllegalArgumentException
   * @throws InstantiationException
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   */
  public List<IN> classifyGibbsUsingPrior(List<IN> sentence, SequenceModel[] priorModels,
      SequenceListener[] priorListeners, double[] modelWts) throws ClassNotFoundException, SecurityException,
      NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException,
      InvocationTargetException {

    if ((priorModels.length + 1) != modelWts.length)
      throw new RuntimeException(
          "modelWts array should be longer than the priorModels array by 1 unit since it also includes the weight of the CRF model at position 0.");

    // System.err.println("Testing using Gibbs sampling.");
    Pair<int[][][], int[]> p = documentToDataAndLabels(sentence);

    List<IN> newDocument = sentence; // reversed if necessary
    if (flags.useReverse) {
      Collections.reverse(sentence);
      newDocument = new ArrayList<IN>(sentence);
      Collections.reverse(sentence);
    }

    CRFCliqueTree<String> cliqueTree = getCliqueTree(p);

    SequenceModel model = cliqueTree;
    SequenceListener listener = cliqueTree;

    SequenceModel[] models = new SequenceModel[priorModels.length + 1];
    models[0] = model;
    for (int i = 1; i < models.length; i++)
      models[i] = priorModels[i - 1];
    model = new FactoredSequenceModel(models, modelWts);

    SequenceListener[] listeners = new SequenceListener[priorListeners.length + 1];
    listeners[0] = listener;
    for (int i = 1; i < listeners.length; i++)
      listeners[i] = priorListeners[i - 1];
    listener = new FactoredSequenceListener(listeners);

    SequenceGibbsSampler sampler = new SequenceGibbsSampler(0, 0, listener);
    int[] sequence = new int[cliqueTree.length()];

    if (flags.initViterbi) {
      TestSequenceModel testSequenceModel = new TestSequenceModel(cliqueTree);
      ExactBestSequenceFinder tagInference = new ExactBestSequenceFinder();
      int[] bestSequence = tagInference.bestSequence(testSequenceModel);
      System.arraycopy(bestSequence, windowSize - 1, sequence, 0, sequence.length);
    } else {
      int[] initialSequence = SequenceGibbsSampler.getRandomSequence(model);
      System.arraycopy(initialSequence, 0, sequence, 0, sequence.length);
    }

    SequenceGibbsSampler.verbose = 0;

    if (flags.annealingType.equalsIgnoreCase("linear")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getLinearSchedule(1.0, flags.numSamples),
          sequence);
    } else if (flags.annealingType.equalsIgnoreCase("exp") || flags.annealingType.equalsIgnoreCase("exponential")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getExponentialSchedule(1.0, flags.annealingRate,
          flags.numSamples), sequence);
    } else {
      throw new RuntimeException("No annealing type specified");
    }

    // System.err.println(ArrayMath.toString(sequence));

    if (flags.useReverse) {
      Collections.reverse(sentence);
    }

    for (int j = 0, dsize = newDocument.size(); j < dsize; j++) {
      IN wi = sentence.get(j);
      if (wi == null) throw new RuntimeException("");
      if (classIndex == null) throw new RuntimeException("");
      wi.set(AnswerAnnotation.class, classIndex.get(sequence[j]));
    }

    if (flags.useReverse) {
      Collections.reverse(sentence);
    }

    return sentence;
  }

  public List<IN> classifyGibbsUsingPrior(List<IN> sentence, SequenceModel priorModel, SequenceListener priorListener,
      double model1Wt, double model2Wt) throws ClassNotFoundException, SecurityException, NoSuchMethodException,
      IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
    // System.err.println("Testing using Gibbs sampling.");
    Pair<int[][][], int[]> p = documentToDataAndLabels(sentence);
    List<IN> newDocument = sentence; // reversed if necessary
    if (flags.useReverse) {
      newDocument = new ArrayList<IN>(sentence);
      Collections.reverse(newDocument);
    }

    CRFCliqueTree<String> cliqueTree = getCliqueTree(p);

    SequenceModel model = cliqueTree;
    SequenceListener listener = cliqueTree;

    model = new FactoredSequenceModel(model, priorModel, model1Wt, model2Wt);
    listener = new FactoredSequenceListener(listener, priorListener);

    SequenceGibbsSampler sampler = new SequenceGibbsSampler(0, 0, listener);
    int[] sequence = new int[cliqueTree.length()];

    if (flags.initViterbi) {
      TestSequenceModel testSequenceModel = new TestSequenceModel(cliqueTree);
      ExactBestSequenceFinder tagInference = new ExactBestSequenceFinder();
      int[] bestSequence = tagInference.bestSequence(testSequenceModel);
      System.arraycopy(bestSequence, windowSize - 1, sequence, 0, sequence.length);
    } else {
      int[] initialSequence = SequenceGibbsSampler.getRandomSequence(model);
      System.arraycopy(initialSequence, 0, sequence, 0, sequence.length);
    }

    SequenceGibbsSampler.verbose = 0;

    if (flags.annealingType.equalsIgnoreCase("linear")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getLinearSchedule(1.0, flags.numSamples),
          sequence);
    } else if (flags.annealingType.equalsIgnoreCase("exp") || flags.annealingType.equalsIgnoreCase("exponential")) {
      sequence = sampler.findBestUsingAnnealing(model, CoolingSchedule.getExponentialSchedule(1.0, flags.annealingRate,
          flags.numSamples), sequence);
    } else {
      throw new RuntimeException("No annealing type specified");
    }

    // System.err.println(ArrayMath.toString(sequence));

    if (flags.useReverse) {
      Collections.reverse(sentence);
    }

    for (int j = 0, dsize = newDocument.size(); j < dsize; j++) {
      IN wi = sentence.get(j);
      if (wi == null) throw new RuntimeException("");
      if (classIndex == null) throw new RuntimeException("");
      wi.set(AnswerAnnotation.class, classIndex.get(sequence[j]));
    }

    if (flags.useReverse) {
      Collections.reverse(sentence);
    }

    return sentence;
  }

  /**
   * Takes a {@link List} of something that extends {@link CoreMap} and prints
   * the likelihood of each possible label at each point.
   *
   * @param document
   *          A {@link List} of something that extends CoreMap.
   */
  @Override
  public void printProbsDocument(List<IN> document) {

    Pair<int[][][], int[]> p = documentToDataAndLabels(document);

    CRFCliqueTree<String> cliqueTree = getCliqueTree(p);

    // for (int i = 0; i < factorTables.length; i++) {
    for (int i = 0; i < cliqueTree.length(); i++) {
      IN wi = document.get(i);
      System.out.print(wi.get(CoreAnnotations.TextAnnotation.class) + '\t');
      for (Iterator<String> iter = classIndex.iterator(); iter.hasNext();) {
        String label = iter.next();
        int index = classIndex.indexOf(label);
        // double prob = Math.pow(Math.E, factorTables[i].logProbEnd(index));
        double prob = cliqueTree.prob(i, index);
        System.out.print(label + '=' + prob);
        if (iter.hasNext()) {
          System.out.print("\t");
        } else {
          System.out.print("\n");
        }
      }
    }
  }

  /**
   * Takes the file, reads it in, and prints out the likelihood of each possible
   * label at each point. This gives a simple way to examine the probability
   * distributions of the CRF. See <code>getCliqueTrees()</code> for more.
   *
   * @param filename
   *          The path to the specified file
   */
  public void printFirstOrderProbs(String filename, DocumentReaderAndWriter<IN> readerAndWriter) {
    // only for the OCR data does this matter
    flags.ocrTrain = false;

    ObjectBank<List<IN>> docs = makeObjectBankFromFile(filename, readerAndWriter);
    printFirstOrderProbsDocuments(docs);
  }

  /**
   * Takes a {@link List} of documents and prints the likelihood of each
   * possible label at each point.
   *
   * @param documents
   *          A {@link List} of {@link List} of INs.
   */
  public void printFirstOrderProbsDocuments(ObjectBank<List<IN>> documents) {
    for (List<IN> doc : documents) {
      printFirstOrderProbsDocument(doc);
      System.out.println();
    }
  }

  /**
   * Want to make arbitrary probability queries? Then this is the method for
   * you. Given the filename, it reads it in and breaks it into documents, and
   * then makes a CRFCliqueTree for each document. you can then ask the clique
   * tree for marginals and conditional probabilities of almost anything you
   * want.
   */
  public List<CRFCliqueTree<String>> getCliqueTrees(String filename, DocumentReaderAndWriter<IN> readerAndWriter) {
    // only for the OCR data does this matter
    flags.ocrTrain = false;

    List<CRFCliqueTree<String>> cts = new ArrayList<CRFCliqueTree<String>>();
    ObjectBank<List<IN>> docs = makeObjectBankFromFile(filename, readerAndWriter);
    for (List<IN> doc : docs) {
      cts.add(getCliqueTree(doc));
    }

    return cts;
  }

  public CRFCliqueTree<String> getCliqueTree(Pair<int[][][], int[]> p) {
    int[][][] data = p.first();

    return CRFCliqueTree.getCalibratedCliqueTree(data, labelIndices, classIndex.size(), classIndex,
        flags.backgroundSymbol, getCliquePotentialFunction());
  }

  public CRFCliqueTree<String> getCliqueTree(List<IN> document) {
    Pair<int[][][], int[]> p = documentToDataAndLabels(document);
    return getCliqueTree(p);
  }

  /**
   * Takes a {@link List} of something that extends {@link CoreMap} and prints
   * the likelihood of each possible label at each point.
   *
   * @param document
   *          A {@link List} of something that extends {@link CoreMap}.
   */
  public void printFirstOrderProbsDocument(List<IN> document) {

    CRFCliqueTree<String> cliqueTree = getCliqueTree(document);

    // for (int i = 0; i < factorTables.length; i++) {
    for (int i = 0; i < cliqueTree.length(); i++) {
      IN wi = document.get(i);
      System.out.print(wi.get(CoreAnnotations.TextAnnotation.class) + '\t');
      for (Iterator<String> iter = classIndex.iterator(); iter.hasNext();) {
        String label = iter.next();
        int index = classIndex.indexOf(label);
        if (i == 0) {
          // double prob = Math.pow(Math.E, factorTables[i].logProbEnd(index));
          double prob = cliqueTree.prob(i, index);
          System.out.print(label + '=' + prob);
          if (iter.hasNext()) {
            System.out.print("\t");
          } else {
            System.out.print("\n");
          }
        } else {
          for (Iterator<String> iter1 = classIndex.iterator(); iter1.hasNext();) {
            String label1 = iter1.next();
            int index1 = classIndex.indexOf(label1);
            // double prob = Math.pow(Math.E, factorTables[i].logProbEnd(new
            // int[]{index1, index}));
            double prob = cliqueTree.prob(i, new int[] { index1, index });
            System.out.print(label1 + '_' + label + '=' + prob);
            if (iter.hasNext() || iter1.hasNext()) {
              System.out.print("\t");
            } else {
              System.out.print("\n");
            }
          }
        }
      }
    }
  }

  /**
   * Train a classifier from documents.
   *
   * @param docs A Collection (perhaps ObjectBank) of documents
   */
  @Override
  public void train(Collection<List<IN>> docs, DocumentReaderAndWriter<IN> readerAndWriter) {
    Timing timer = new Timing();
    timer.start();
    makeAnswerArraysAndTagIndex(docs);
    long elapsedMs = timer.stop();
    System.err.println("Time to convert docs to feature indices: " + Timing.toSecondsString(elapsedMs) + " seconds");
    if (flags.exportFeatures != null) {
      timer.start();
      CRFFeatureExporter<IN> featureExporter = new CRFFeatureExporter<IN>(this);
      featureExporter.printFeatures(flags.exportFeatures, docs);
      elapsedMs = timer.stop();
      System.err.println("Time to export features: " + Timing.toSecondsString(elapsedMs) + " seconds");
    }
    for (int i = 0; i <= flags.numTimesPruneFeatures; i++) {
      timer.start();
      Pair<int[][][][], int[][]> dataAndLabels = documentsToDataAndLabels(docs);
      elapsedMs = timer.stop();
      System.err.println("Time to convert docs to data/labels: " + Timing.toSecondsString(elapsedMs) + " seconds");

      Evaluator[] evaluators = null;
      if (flags.evaluateIters > 0) {
        List<Evaluator> evaluatorList = new ArrayList<Evaluator>();
        evaluatorList.add(new MemoryEvaluator());
        if (flags.evaluateTrain) {
          CRFClassifierEvaluator<IN> crfEvaluator = new CRFClassifierEvaluator<IN>("Train set", this);
          List<Pair<int[][][], int[]>> trainDataAndLabels = new ArrayList<Pair<int[][][], int[]>>();
          for (int j = 0; j < dataAndLabels.first().length; j++) {
            Pair<int[][][], int[]> p = new Pair<int[][][], int[]>(dataAndLabels.first()[j], dataAndLabels.second()[j]);
            trainDataAndLabels.add(p);
          }
          crfEvaluator.setTestData(docs, trainDataAndLabels);
          crfEvaluator.setEvalCmd(flags.evalCmd);
          evaluatorList.add(crfEvaluator);
        }
        if (flags.testFile != null) {
          CRFClassifierEvaluator<IN> crfEvaluator = new CRFClassifierEvaluator<IN>("Test set (" + flags.testFile + ")",
              this);
          ObjectBank<List<IN>> testObjBank = makeObjectBankFromFile(flags.testFile, readerAndWriter);
          List<Pair<int[][][], int[]>> testDataAndLabels = documentsToDataAndLabelsList(testObjBank);
          crfEvaluator.setTestData(testObjBank, testDataAndLabels);
          crfEvaluator.setEvalCmd(flags.evalCmd);
          evaluatorList.add(crfEvaluator);
        }
        if (flags.testFiles != null) {
          String[] testFiles = flags.testFiles.split(",");
          for (String testFile : testFiles) {
            CRFClassifierEvaluator<IN> crfEvaluator = new CRFClassifierEvaluator<IN>("Test set ("
                + testFile + ")", this);
            ObjectBank<List<IN>> testObjBank = makeObjectBankFromFile(testFile, readerAndWriter);
            List<Pair<int[][][], int[]>> testDataAndLabels = documentsToDataAndLabelsList(testObjBank);
            crfEvaluator.setTestData(testObjBank, testDataAndLabels);
            crfEvaluator.setEvalCmd(flags.evalCmd);
            evaluatorList.add(crfEvaluator);
          }
        }
        evaluators = new Evaluator[evaluatorList.size()];
        evaluatorList.toArray(evaluators);
      }

      if (flags.numTimesPruneFeatures == i) {
        docs = null; // hopefully saves memory
      }
      // save feature index to disk and read in later
      File featIndexFile = null;

      int numFeatures = featureIndex.size();
      if (flags.saveFeatureIndexToDisk) {
        try {
          System.err.println("Writing feature index to temporary file.");
          featIndexFile = IOUtils.writeObjectToTempFile(featureIndex, "featIndex" + i + ".tmp");
          featureIndex = null;
        } catch (IOException e) {
          throw new RuntimeException("Could not open temporary feature index file for writing.");
        }
      }

      // first index is the number of the document
      // second index is position in the document also the index of the
      // clique/factor table
      // third index is the number of elements in the clique/window thase
      // features are for (starting with last element)
      // fourth index is position of the feature in the array that holds them
      // element in data[i][j][k][m] is the index of the mth feature occurring
      // in position k of the jth clique of the ith document
      int[][][][] data = dataAndLabels.first();
      // first index is the number of the document
      // second index is the position in the document
      // element in labels[i][j] is the index of the correct label (if it
      // exists) at position j in document i
      int[][] labels = dataAndLabels.second();

      if (flags.loadProcessedData != null) {
        List<List<CRFDatum<Collection<String>, String>>> processedData = loadProcessedData(flags.loadProcessedData);
        if (processedData != null) {
          // enlarge the data and labels array
          int[][][][] allData = new int[data.length + processedData.size()][][][];
          int[][] allLabels = new int[labels.length + processedData.size()][];
          System.arraycopy(data, 0, allData, 0, data.length);
          System.arraycopy(labels, 0, allLabels, 0, labels.length);
          // add to the data and labels array
          addProcessedData(processedData, allData, allLabels, data.length);
          data = allData;
          labels = allLabels;
        }
      }

      if (flags.nonLinearCRF) {
        if (flags.secondOrderNonLinear) {
          CRFNonLinearSecondOrderLogConditionalObjectiveFunction func = new CRFNonLinearSecondOrderLogConditionalObjectiveFunction(data, labels,
            windowSize, classIndex, labelIndices, map, flags, nodeFeatureIndicesMap, edgeFeatureIndicesMap);
          double[] allWeights = trainWeightsUsingNonLinearCRF(func);
          Quadruple<double[][], double[][], double[][], double[][]> params = func.separateWeights(allWeights);
          this.inputLayerWeights4Edge = params.first();
          this.outputLayerWeights4Edge = params.second();
          this.inputLayerWeights = params.third();
          this.outputLayerWeights = params.fourth();
          System.err.println("Edge Output Layer Weights:");
          for (int ii = 0; ii < outputLayerWeights4Edge.length; ii++) {
            System.err.print("[ ");
            for (int jj = 0; jj < outputLayerWeights4Edge[ii].length; jj++) {
              System.err.print(outputLayerWeights4Edge[ii][jj] + " ");
            }
            System.err.println("]");
          }
          System.err.println("Node Output Layer Weights:");
          for (int ii = 0; ii < outputLayerWeights.length; ii++) {
            System.err.print("[ ");
            for (int jj = 0; jj < outputLayerWeights[ii].length; jj++) {
              System.err.print(outputLayerWeights[ii][jj] + " ");
            }
            System.err.println("]");
          }
        } else {
          CRFNonLinearLogConditionalObjectiveFunction func = new CRFNonLinearLogConditionalObjectiveFunction(data, labels,
            windowSize, classIndex, labelIndices, map, flags, nodeFeatureIndicesMap, edgeFeatureIndicesMap);
          double[] allWeights = trainWeightsUsingNonLinearCRF(func);
          Triple<double[][], double[][], double[][]> params = func.separateWeights(allWeights);
          this.linearWeights = params.first();
          this.inputLayerWeights = params.second();
          this.outputLayerWeights = params.third();
          System.err.println("Output Layer Weights:");
          for (int ii = 0; ii < outputLayerWeights.length; ii++) {
            System.err.print("[ ");
            for (int jj = 0; jj < outputLayerWeights[ii].length; jj++) {
              System.err.print(outputLayerWeights[ii][jj] + " ");
            }
            System.err.println("]");
          }
        }
      } else {
        double[] oneDimWeights = null;
        if (flags.useFloat) {
          oneDimWeights = trainWeightsUsingFloatCRF(data, labels, i);  
        } else if (flags.numLopExpert > 1) {
          oneDimWeights = trainWeightsUsingLopCRF(numFeatures, data, labels, evaluators, i);  
        } else {
          oneDimWeights = trainWeightsUsingDoubleCRF(data, labels, evaluators, i);  
        }
        this.weights = CRFLogConditionalObjectiveFunction.to2D(oneDimWeights, labelIndices, map);
      }

      // save feature index to disk and read in later
      if (flags.saveFeatureIndexToDisk) {
        try {
          System.err.println("Reading temporary feature index file.");
          featureIndex = (Index<String>) IOUtils.readObjectFromFile(featIndexFile);
        } catch (Exception e) {
          throw new RuntimeException("Could not open temporary feature index file for reading.");
        }
      }

      if (i != flags.numTimesPruneFeatures) {
        dropFeaturesBelowThreshold(flags.featureDiffThresh);
        System.err.println("Removing features with weight below " + flags.featureDiffThresh + " and retraining...");
      }

    }
  }

  protected double[] trainWeightsUsingFloatCRF(int[][][][] data, int[][] labels, int pruneFeatureItr) {
    CRFLogConditionalObjectiveFloatFunction func = new CRFLogConditionalObjectiveFloatFunction(data, labels,
        featureIndex, windowSize, classIndex, labelIndices, map, flags.backgroundSymbol, flags.sigma);

    QNMinimizer minimizer;
    if (flags.interimOutputFreq != 0) {
      FloatFunction monitor = new ResultStoringFloatMonitor(flags.interimOutputFreq, flags.serializeTo);
      minimizer = new QNMinimizer(monitor);
    } else {
      minimizer = new QNMinimizer();
    }

    if (pruneFeatureItr == 0) {
      minimizer.setM(flags.QNsize);
    } else {
      minimizer.setM(flags.QNsize2);
    }

    float[] initialWeights;
    if (flags.initialWeights == null) {
      initialWeights = func.initial();
    } else {
      try {
        System.err.println("Reading initial weights from file " + flags.initialWeights);
        DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(
            flags.initialWeights))));
        initialWeights = Convert.readFloatArr(dis);
      } catch (IOException e) {
        throw new RuntimeException("Could not read from float initial weight file " + flags.initialWeights);
      }
    }
    System.err.println("numWeights: " + initialWeights.length);
    float[] weights = minimizer.minimize(func, (float) flags.tolerance, initialWeights);
    return ArrayMath.floatArrayToDoubleArray(weights);
  }

  protected int[][][][] createPartialDataForLOP(int lopIter, int[][][][] data) {
    int[] oldFeatures = null;
    int oldFeatureIndex = -1;
    ArrayList<Integer> newFeatureList = new ArrayList<Integer>(1000);
    Set<Integer> featureIndicesSet = featureIndicesSetArray.get(lopIter);

    int[][][][] newData = new int[data.length][][][];
    for (int i = 0; i < data.length; i++) {
      newData[i] = new int[data[i].length][][];
      for (int j = 0; j < data[i].length; j++) {
        newData[i][j] = new int[data[i][j].length][];
        for (int k = 0; k < data[i][j].length; k++) {
          oldFeatures = data[i][j][k];
          newFeatureList.clear();
          for (int l = 0; l < oldFeatures.length; l++) {
            oldFeatureIndex = oldFeatures[l];
            if (featureIndicesSet.contains(oldFeatureIndex)) {
              newFeatureList.add(oldFeatureIndex);
            }
          }
          newData[i][j][k] = new int[newFeatureList.size()];
          for (int l = 0; l < newFeatureList.size(); ++l) {
            newData[i][j][k][l] = newFeatureList.get(l);
          }
        }
      }
    }

    return newData;
  }

  /*
  protected int[][] getFeatureBoundaryIndices(int numFeatures, int numLopExpert) {
    // first find begin/end feature index for each expert
    int interval = numFeatures / numLopExpert;
    int[] beginFeatureIndices = new int[numLopExpert];
    int[] begin1DParamIndices = new int[numLopExpert];
    int[] endFeatureIndices = new int[numLopExpert];
    int[] end1DParamIndices = new int[numLopExpert];
    for (int lopIter = 0; lopIter < numLopExpert; lopIter++) {
      int beginIndex = lopIter * interval;
      int endIndex = (lopIter+1) * interval;
      if (lopIter == numLopExpert - 1) {
        endIndex = numFeatures;
      }
      int begin1D = 0;
      for (int fIndex = 0; fIndex < beginIndex; fIndex++) {
        begin1D += labelIndices[map[fIndex]].size();
      }
      int end1D = begin1D;
      for (int fIndex = beginIndex; fIndex < endIndex; fIndex++) {
        end1D += labelIndices[map[fIndex]].size();
      }
      beginFeatureIndices[lopIter] = beginIndex;
      endFeatureIndices[lopIter] = endIndex;
      begin1DParamIndices[lopIter] = begin1D;
      end1DParamIndices[lopIter] = end1D;
    }
    int[][] boundaryIndices = new int[4][];
    boundaryIndices[0] = beginFeatureIndices;
    boundaryIndices[1] = endFeatureIndices;
    boundaryIndices[2] = begin1DParamIndices;
    boundaryIndices[3] = end1DParamIndices;
    return boundaryIndices;
  }
  */

  protected void getFeatureBoundaryIndices(int numFeatures, int numLopExpert) {
    // first find begin/end feature index for each expert
    int interval = numFeatures / numLopExpert;
    featureIndicesSetArray = new ArrayList<Set<Integer>>(numLopExpert);
    featureIndicesListArray =  new ArrayList<List<Integer>>(numLopExpert);
    for (int i = 0; i < numLopExpert; i++) {
      featureIndicesSetArray.add(new HashSet<Integer>(interval));
      featureIndicesListArray.add(new ArrayList<Integer>(interval));
    }
    if (flags.randomLopFeatureSplit) {
      for (int fIndex = 0; fIndex < numFeatures; fIndex++) {
        int lopIter = random.nextInt(numLopExpert);
        featureIndicesSetArray.get(lopIter).add(fIndex);
        featureIndicesListArray.get(lopIter).add(fIndex);
      }
    } else {
      for (int lopIter = 0; lopIter < numLopExpert; lopIter++) {
        int beginIndex = lopIter * interval;
        int endIndex = (lopIter+1) * interval;
        if (lopIter == numLopExpert - 1) {
          endIndex = numFeatures;
        }
        for (int fIndex = beginIndex; fIndex < endIndex; fIndex++) {
          featureIndicesSetArray.get(lopIter).add(fIndex);
          featureIndicesListArray.get(lopIter).add(fIndex);
        }
      }
    }
    for (int lopIter = 0; lopIter < numLopExpert; lopIter++) {
      Collections.sort(featureIndicesListArray.get(lopIter));
    }
  }

  protected double[] trainWeightsUsingLopCRF(int numFeatures, int[][][][] data, int[][] labels, Evaluator[] evaluators, int pruneFeatureItr) {
    int numLopExpert = flags.numLopExpert;
    double[][] lopExpertWeights = new double[numLopExpert][];

    getFeatureBoundaryIndices(numFeatures, numLopExpert);

    if (flags.initialLopWeights != null) {
      try {
        //TODO(mengqiu) use GZIPInputStream
        System.err.println("Reading initial LOP weights from file " + flags.initialLopWeights + " ...");
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(new File(flags.initialLopWeights))));
        String line = null;
        List<double[]> listOfWeights = new ArrayList<double[]>(numLopExpert);
        while ((line = br.readLine()) != null) {
          line = line.trim();
          String[] parts = line.split("\t");
          double[] wArr = new double[parts.length];
          for (int i = 0; i < parts.length; i++) {
            wArr[i] = Double.parseDouble(parts[i]);
          }
          listOfWeights.add(wArr);
        }
        assert(listOfWeights.size() == numLopExpert);
        System.err.println("Done!");
        for (int i = 0; i < numLopExpert; i++)
          lopExpertWeights[i] = listOfWeights.get(i);
        // DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(
        //     flags.initialLopWeights))));
        // initialScales = Convert.readDoubleArr(dis);
      } catch (IOException e) {
        throw new RuntimeException("Could not read from double initial LOP weights file " + flags.initialLopWeights);
      }
    } else {
      for (int lopIter = 0; lopIter < numLopExpert; lopIter++) {
        int[][][][] partialData = createPartialDataForLOP(lopIter, data);
        if (flags.randomLopWeights) {
          lopExpertWeights[lopIter] = initWeightsUsingDoubleCRF(partialData, labels, evaluators, pruneFeatureItr);
        } else {
          lopExpertWeights[lopIter] = trainWeightsUsingDoubleCRF(partialData, labels, evaluators, pruneFeatureItr);
        }
      }
      if (flags.includeFullCRFInLOP) {
        double[][] newLopExpertWeights = new double[numLopExpert+1][];
        System.arraycopy(lopExpertWeights, 0, newLopExpertWeights, 0, lopExpertWeights.length);
        if (flags.randomLopWeights) {
          newLopExpertWeights[numLopExpert] = initWeightsUsingDoubleCRF(data, labels, evaluators, pruneFeatureItr);
        } else {
          newLopExpertWeights[numLopExpert] = trainWeightsUsingDoubleCRF(data, labels, evaluators, pruneFeatureItr);
        }

        Set<Integer> newSet = new HashSet<Integer>(numFeatures);
        List<Integer> newList = new ArrayList<Integer>(numFeatures);
        for (int fIndex = 0; fIndex < numFeatures; fIndex++) {
          newSet.add(fIndex);
          newList.add(fIndex);
        }
        featureIndicesSetArray.add(newSet);
        featureIndicesListArray.add(newList);

        numLopExpert += 1;
        lopExpertWeights = newLopExpertWeights;
      }
    }

    // Dumb scales
    // double[] lopScales = new double[numLopExpert];
    // Arrays.fill(lopScales, 1.0);
    CRFLogConditionalObjectiveFunctionForLOP func = new CRFLogConditionalObjectiveFunctionForLOP(data, labels, lopExpertWeights,
        windowSize, classIndex, labelIndices, map, flags.backgroundSymbol, numLopExpert, featureIndicesSetArray, featureIndicesListArray,
        flags.backpropLopTraining);
    Minimizer minimizer = getMinimizer();

    double[] initialScales;
    //TODO(mengqiu) clean this part up when backpropLogTraining == true
    if (flags.initialLopScales == null) {
      initialScales = func.initial();
    } else {
      try {
        System.err.println("Reading initial LOP scales from file " + flags.initialLopScales);
        DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(
            flags.initialLopScales))));
        initialScales = Convert.readDoubleArr(dis);
      } catch (IOException e) {
        throw new RuntimeException("Could not read from double initial LOP scales file " + flags.initialLopScales);
      }
    }

    double[] learnedParams = minimizer.minimize(func, flags.tolerance, initialScales);
    double[] rawScales = func.separateLopScales(learnedParams);
    double[] lopScales = ArrayMath.softmax(rawScales);
    System.err.println("After SoftMax Transformation, learned scales are:");
    for (int lopIter = 0; lopIter < numLopExpert; lopIter++) {
      System.err.println("lopScales[" + lopIter + "] = " + lopScales[lopIter]);
    }
    double[][] learnedLopExpertWeights = lopExpertWeights;
    if (flags.backpropLopTraining) {
      learnedLopExpertWeights = func.separateLopExpertWeights(learnedParams);
    }
    return CRFLogConditionalObjectiveFunctionForLOP.combineAndScaleLopWeights(numLopExpert, learnedLopExpertWeights, lopScales);
  }

  protected double[] initWeightsUsingDoubleCRF(int[][][][] data, int[][] labels, Evaluator[] evaluators, int pruneFeatureItr) {
    CRFLogConditionalObjectiveFunction func = new CRFLogConditionalObjectiveFunction(data, labels, featureIndex,
        windowSize, classIndex, labelIndices, map, flags.backgroundSymbol, flags.sigma);
    return func.initial();
  }

  protected double[] trainWeightsUsingNonLinearCRF(AbstractCachingDiffFunction func) {
    Minimizer minimizer = getMinimizer();

    double[] initialWeights;
    if (flags.initialWeights == null) {
      initialWeights = func.initial();
    } else {
      try {
        System.err.println("Reading initial weights from file " + flags.initialWeights);
        DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(
            flags.initialWeights))));
        initialWeights = Convert.readDoubleArr(dis);
      } catch (IOException e) {
        throw new RuntimeException("Could not read from double initial weight file " + flags.initialWeights);
      }
    }
    System.err.println("numWeights: " + initialWeights.length);

    if (flags.testObjFunction) {
      StochasticDiffFunctionTester tester = new StochasticDiffFunctionTester(func);
      if (tester.testSumOfBatches(initialWeights, 1e-4)) {
        System.err.println("Testing complete... exiting");
        System.exit(1);
      } else {
        System.err.println("Testing failed....exiting");
        System.exit(1);
      }

    }
    //check gradient
    if (flags.checkGradient) {
      if (func.gradientCheck()) {
        System.err.println("gradient check passed");
      } else {
        throw new RuntimeException("gradient check failed");
      }
    }
    double[] w = minimizer.minimize(func, flags.tolerance, initialWeights);
    return w;
  }

  protected double[] trainWeightsUsingDoubleCRF(int[][][][] data, int[][] labels, Evaluator[] evaluators, int pruneFeatureItr) {
    CRFLogConditionalObjectiveFunction func = new CRFLogConditionalObjectiveFunction(data, labels, featureIndex,
        windowSize, classIndex, labelIndices, map, flags.backgroundSymbol, flags.sigma);
    if (evaluators != null) {
      for (Evaluator eval : evaluators) {
        if (eval instanceof CRFClassifierEvaluator) {
          ((CRFClassifierEvaluator) eval).setHelperFunction(func);
        }
      }
    }
    Minimizer minimizer = getMinimizer(pruneFeatureItr, evaluators);

    double[] initialWeights;
    if (flags.initialWeights == null) {
      initialWeights = func.initial();
    } else {
      try {
        System.err.println("Reading initial weights from file " + flags.initialWeights);
        DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(
            flags.initialWeights))));
        initialWeights = Convert.readDoubleArr(dis);
      } catch (IOException e) {
        throw new RuntimeException("Could not read from double initial weight file " + flags.initialWeights);
      }
    }
    System.err.println("numWeights: " + initialWeights.length);

    if (flags.testObjFunction) {
      StochasticDiffFunctionTester tester = new StochasticDiffFunctionTester(func);
      if (tester.testSumOfBatches(initialWeights, 1e-4)) {
        System.err.println("Testing complete... exiting");
        System.exit(1);
      } else {
        System.err.println("Testing failed....exiting");
        System.exit(1);
      }

    }
    //check gradient
    if (flags.checkGradient) {
      if (func.gradientCheck()) {
        System.err.println("gradient check passed");
      } else {
        throw new RuntimeException("gradient check failed");
      }
    }
    double[] ws = minimizer.minimize(func, flags.tolerance, initialWeights);
    if (flags.inputDropOut != 0.0) {
      // scale the weights since they won't be dropped at test time
      ArrayMath.multiplyInPlace(ws, 1.0/(1.0 - flags.inputDropOut));
      System.err.printf("Scaled weights by %f", 1.0/(1.0 - flags.inputDropOut));
    }
    return ws;
  }


  protected Minimizer getMinimizer() {
    return getMinimizer(0, null);
  }

  protected Minimizer getMinimizer(int featurePruneIteration, Evaluator[] evaluators) {
    Minimizer minimizer = null;
    if (flags.useQN) {

      int QNmem;
      if (featurePruneIteration == 0) {
        QNmem = flags.QNsize;
      } else {
        QNmem = flags.QNsize2;
      }

      if (flags.interimOutputFreq != 0) {
        Function monitor = new ResultStoringMonitor(flags.interimOutputFreq, flags.serializeTo);
        minimizer = new QNMinimizer(monitor, QNmem, flags.useRobustQN);
      } else {
        minimizer = new QNMinimizer(QNmem, flags.useRobustQN);
      }
    } else if (flags.useInPlaceSGD) {
      StochasticInPlaceMinimizer<DiffFunction> sgdMinimizer =
              new StochasticInPlaceMinimizer<DiffFunction>(flags.sigma, flags.SGDPasses, flags.tuneSampleSize);
      if (flags.useSGDtoQN) {
        QNMinimizer qnMinimizer;
        int QNmem;
        if (featurePruneIteration == 0) {
          QNmem = flags.QNsize;
        } else {
          QNmem = flags.QNsize2;
        }
        if (flags.interimOutputFreq != 0) {
          Function monitor = new ResultStoringMonitor(flags.interimOutputFreq, flags.serializeTo);
          qnMinimizer = new QNMinimizer(monitor, QNmem, flags.useRobustQN);
        } else {
          qnMinimizer = new QNMinimizer(QNmem, flags.useRobustQN);
        }
        minimizer = new HybridMinimizer(sgdMinimizer, qnMinimizer, flags.SGDPasses);
      } else {
        minimizer = sgdMinimizer;
      }
    } else if (flags.useSGDtoQN) {
      minimizer = new SGDToQNMinimizer(flags.initialGain, flags.stochasticBatchSize,
                                       flags.SGDPasses, flags.QNPasses, flags.SGD2QNhessSamples,
                                       flags.QNsize, flags.outputIterationsToFile);
    } else if (flags.useSMD) {
      minimizer = new SMDMinimizer(flags.initialGain, flags.stochasticBatchSize, flags.stochasticMethod,
          flags.SGDPasses);
    } else if (flags.useSGD) {
      minimizer = new SGDMinimizer(flags.initialGain, flags.stochasticBatchSize);
    } else if (flags.useScaledSGD) {
      minimizer = new ScaledSGDMinimizer(flags.initialGain, flags.stochasticBatchSize, flags.SGDPasses,
          flags.scaledSGDMethod);
    } else if (flags.l1reg > 0.0) {
      minimizer = ReflectionLoading.loadByReflection("edu.stanford.nlp.optimization.OWLQNMinimizer", flags.l1reg);
    }

    if (minimizer instanceof HasEvaluators) {
      ((HasEvaluators) minimizer).setEvaluators(flags.evaluateIters, evaluators);
    }
    if (minimizer == null) {
      throw new RuntimeException("No minimizer assigned!");
    }

    return minimizer;
  }

  /**
   * Creates a new CRFDatum from the preprocessed allData format, given the
   * document number, position number, and a List of Object labels.
   *
   * @return A new CRFDatum
   */
  protected List<CRFDatum<? extends Collection<String>, ? extends CharSequence>> extractDatumSequence(int[][][] allData, int beginPosition, int endPosition,
      List<IN> labeledWordInfos) {
    List<CRFDatum<? extends Collection<String>, ? extends CharSequence>> result = new ArrayList<CRFDatum<? extends Collection<String>, ? extends CharSequence>>();
    int beginContext = beginPosition - windowSize + 1;
    if (beginContext < 0) {
      beginContext = 0;
    }
    // for the beginning context, add some dummy datums with no features!
    // TODO: is there any better way to do this?
    for (int position = beginContext; position < beginPosition; position++) {
      List<Collection<String>> cliqueFeatures = new ArrayList<Collection<String>>();
      for (int i = 0; i < windowSize; i++) {
        // create a feature list
        cliqueFeatures.add(Collections.<String>emptyList());
      }
      CRFDatum<Collection<String>, String> datum = new CRFDatum<Collection<String>, String>(cliqueFeatures,
              labeledWordInfos.get(position).get(AnswerAnnotation.class));
      result.add(datum);
    }
    // now add the real datums
    for (int position = beginPosition; position <= endPosition; position++) {
      List<Collection<String>> cliqueFeatures = new ArrayList<Collection<String>>();
      for (int i = 0; i < windowSize; i++) {
        // create a feature list
        Collection<String> features = new ArrayList<String>();
        for (int j = 0; j < allData[position][i].length; j++) {
          features.add(featureIndex.get(allData[position][i][j]));
        }
        cliqueFeatures.add(features);
      }
      CRFDatum<Collection<String>,String> datum = new CRFDatum<Collection<String>,String>(cliqueFeatures,
              labeledWordInfos.get(position).get(AnswerAnnotation.class));
      result.add(datum);
    }
    return result;
  }

  /**
   * Adds the List of Lists of CRFDatums to the data and labels arrays, treating
   * each datum as if it were its own document. Adds context labels in addition
   * to the target label for each datum, meaning that for a particular document,
   * the number of labels will be windowSize-1 greater than the number of
   * datums.
   *
   * @param processedData
   *          a List of Lists of CRFDatums
   */
  protected void addProcessedData(List<List<CRFDatum<Collection<String>, String>>> processedData, int[][][][] data,
      int[][] labels, int offset) {
    for (int i = 0, pdSize = processedData.size(); i < pdSize; i++) {
      int dataIndex = i + offset;
      List<CRFDatum<Collection<String>, String>> document = processedData.get(i);
      int dsize = document.size();
      labels[dataIndex] = new int[dsize];
      data[dataIndex] = new int[dsize][][];
      for (int j = 0; j < dsize; j++) {
        CRFDatum<Collection<String>, String> crfDatum = document.get(j);
        // add label, they are offset by extra context
        labels[dataIndex][j] = classIndex.indexOf(crfDatum.label());
        // add features
        List<Collection<String>> cliques = crfDatum.asFeatures();
        int csize = cliques.size();
        data[dataIndex][j] = new int[csize][];
        for (int k = 0; k < csize; k++) {
          Collection<String> features = cliques.get(k);

          // Debug only: Remove
          // if (j < windowSize) {
          // System.err.println("addProcessedData: Features Size: " +
          // features.size());
          // }

          data[dataIndex][j][k] = new int[features.size()];

          int m = 0;
          try {
            for (String feature : features) {
              // System.err.println("feature " + feature);
              // if (featureIndex.indexOf(feature)) ;
              if (featureIndex == null) {
                System.out.println("Feature is NULL!");
              }
              data[dataIndex][j][k][m] = featureIndex.indexOf(feature);
              m++;
            }
          } catch (Exception e) {
            e.printStackTrace();
            System.err.printf("[index=%d, j=%d, k=%d, m=%d]\n", dataIndex, j, k, m);
            System.err.println("data.length                    " + data.length);
            System.err.println("data[dataIndex].length         " + data[dataIndex].length);
            System.err.println("data[dataIndex][j].length      " + data[dataIndex][j].length);
            System.err.println("data[dataIndex][j][k].length   " + data[dataIndex][j].length);
            System.err.println("data[dataIndex][j][k][m]       " + data[dataIndex][j][k][m]);
            return;
          }
        }
      }
    }
  }

  protected static void saveProcessedData(List datums, String filename) {
    System.err.print("Saving processsed data of size " + datums.size() + " to serialized file...");
    ObjectOutputStream oos = null;
    try {
      oos = new ObjectOutputStream(new FileOutputStream(filename));
      oos.writeObject(datums);
    } catch (IOException e) {
      // do nothing
    } finally {
      IOUtils.closeIgnoringExceptions(oos);
    }
    System.err.println("done.");
  }

  protected static List<List<CRFDatum<Collection<String>, String>>> loadProcessedData(String filename) {
    System.err.print("Loading processed data from serialized file...");
    ObjectInputStream ois = null;
    List<List<CRFDatum<Collection<String>, String>>> result = Collections.emptyList();
    try {
      ois = new ObjectInputStream(new FileInputStream(filename));
      result = (List<List<CRFDatum<Collection<String>, String>>>) ois.readObject();
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      IOUtils.closeIgnoringExceptions(ois);
    }
    System.err.println("done. Got " + result.size() + " datums.");
    return result;
  }

  public void loadTextClassifier(String text, Properties props) throws ClassCastException, IOException,
      ClassNotFoundException, InstantiationException, IllegalAccessException {
    // System.err.println("DEBUG: in loadTextClassifier");
    System.err.println("Loading Text Classifier from " + text);
    BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(text))));

    String line = br.readLine();
    // first line should be this format:
    // labelIndices.length=\t%d
    String[] toks = line.split("\\t");
    if (!toks[0].equals("labelIndices.length=")) {
      throw new RuntimeException("format error");
    }
    int size = Integer.parseInt(toks[1]);
    labelIndices = new HashIndex[size];
    for (int labelIndicesIdx = 0; labelIndicesIdx < size; labelIndicesIdx++) {
      line = br.readLine();
      // first line should be this format:
      // labelIndices.length=\t%d
      // labelIndices[0].size()=\t%d
      toks = line.split("\\t");
      if (!(toks[0].startsWith("labelIndices[") && toks[0].endsWith("].size()="))) {
        throw new RuntimeException("format error");
      }
      int labelIndexSize = Integer.parseInt(toks[1]);
      labelIndices[labelIndicesIdx] = new HashIndex<CRFLabel>();
      int count = 0;
      while (count < labelIndexSize) {
        line = br.readLine();
        toks = line.split("\\t");
        int idx = Integer.parseInt(toks[0]);
        if (count != idx) {
          throw new RuntimeException("format error");
        }

        String[] crflabelstr = toks[1].split(" ");
        int[] crflabel = new int[crflabelstr.length];
        for (int i = 0; i < crflabelstr.length; i++) {
          crflabel[i] = Integer.parseInt(crflabelstr[i]);
        }
        CRFLabel crfL = new CRFLabel(crflabel);

        labelIndices[labelIndicesIdx].add(crfL);
        count++;
      }
    }

    /**************************************/
    System.err.printf("DEBUG: labelIndices.length=\t%d\n", labelIndices.length);
    for (int i = 0; i < labelIndices.length; i++) {
      System.err.printf("DEBUG: labelIndices[%d].size()=\t%d\n", i, labelIndices[i].size());
      for (int j = 0; j < labelIndices[i].size(); j++) {
        int[] label = labelIndices[i].get(j).getLabel();
        List<Integer> list = new ArrayList<Integer>();
        for (int l : label) {
          list.add(l);
        }
        System.err.printf("DEBUG: %d\t%s\n", j, StringUtils.join(list, " "));
      }
    }
    /**************************************/

    line = br.readLine();
    toks = line.split("\\t");
    if (!toks[0].equals("classIndex.size()=")) {
      throw new RuntimeException("format error");
    }
    int classIndexSize = Integer.parseInt(toks[1]);
    classIndex = new HashIndex<String>();
    int count = 0;
    while (count < classIndexSize) {
      line = br.readLine();
      toks = line.split("\\t");
      int idx = Integer.parseInt(toks[0]);
      if (count != idx) {
        throw new RuntimeException("format error");
      }
      classIndex.add(toks[1]);
      count++;
    }

    /******************************************/
    System.err.printf("DEBUG: classIndex.size()=\t%d\n", classIndex.size());
    for (int i = 0; i < classIndex.size(); i++) {
      System.err.printf("DEBUG: %d\t%s\n", i, classIndex.get(i));
    }
    /******************************************/

    line = br.readLine();
    toks = line.split("\\t");
    if (!toks[0].equals("featureIndex.size()=")) {
      throw new RuntimeException("format error");
    }
    int featureIndexSize = Integer.parseInt(toks[1]);
    featureIndex = new HashIndex<String>();
    count = 0;
    while (count < featureIndexSize) {
      line = br.readLine();
      toks = line.split("\\t");
      int idx = Integer.parseInt(toks[0]);
      if (count != idx) {
        throw new RuntimeException("format error");
      }
      featureIndex.add(toks[1]);
      count++;
    }

    /***************************************/
    System.err.printf("DEBUG: featureIndex.size()=\t%d\n", featureIndex.size());
    /***************************************/

    /*
      for(int i = 0; i < featureIndex.size(); i++) {
        System.err.printf("DEBUG: %d\t%s\n", i, featureIndex.get(i));
      }
    */
    /***************************************/

    line = br.readLine();
    if (!line.equals("<flags>")) {
      throw new RuntimeException("format error");
    }
    Properties p = new Properties();
    line = br.readLine();

    while (!line.equals("</flags>")) {
      // System.err.println("DEBUG: flags line: "+line);
      String[] keyValue = line.split("=");
      // System.err.printf("DEBUG: p.setProperty(%s,%s)\n", keyValue[0],
      // keyValue[1]);
      p.setProperty(keyValue[0], keyValue[1]);
      line = br.readLine();
    }

    // System.err.println("DEBUG: out from flags");
    flags = new SeqClassifierFlags(p);
    System.err.println("DEBUG: <flags>");
    System.err.print(flags.toString());
    System.err.println("DEBUG: </flags>");

    if (flags.nonLinearCRF) {
      line = br.readLine();
      toks = line.split("\\t");
      if (!toks[0].equals("nodeFeatureIndicesMap.size()=")) {
        throw new RuntimeException("format error");
      }
      int nodeFeatureIndicesMapSize = Integer.parseInt(toks[1]);
      nodeFeatureIndicesMap = new HashIndex<Integer>();
      count = 0;
      while (count < nodeFeatureIndicesMapSize) {
        line = br.readLine();
        toks = line.split("\\t");
        int idx = Integer.parseInt(toks[0]);
        if (count != idx) {
          throw new RuntimeException("format error");
        }
        nodeFeatureIndicesMap.add(Integer.parseInt(toks[1]));
        count++;
      }

      /***************************************/
      System.err.printf("DEBUG: nodeFeatureIndicesMap.size()=\t%d\n", nodeFeatureIndicesMap.size());
      /***************************************/

      line = br.readLine();
      toks = line.split("\\t");
      if (!toks[0].equals("edgeFeatureIndicesMap.size()=")) {
        throw new RuntimeException("format error");
      }
      int edgeFeatureIndicesMapSize = Integer.parseInt(toks[1]);
      edgeFeatureIndicesMap = new HashIndex<Integer>();
      count = 0;
      while (count < edgeFeatureIndicesMapSize) {
        line = br.readLine();
        toks = line.split("\\t");
        int idx = Integer.parseInt(toks[0]);
        if (count != idx) {
          throw new RuntimeException("format error");
        }
        edgeFeatureIndicesMap.add(Integer.parseInt(toks[1]));
        count++;
      }

      /***************************************/
      System.err.printf("DEBUG: edgeFeatureIndicesMap.size()=\t%d\n", edgeFeatureIndicesMap.size());
      /***************************************/

      int  weightsLength = -1;
      if (flags.secondOrderNonLinear) {
        line = br.readLine();
        toks = line.split("\\t");
        if (!toks[0].equals("inputLayerWeights4Edge.length=")) {
          throw new RuntimeException("format error");
        }
        weightsLength = Integer.parseInt(toks[1]);
        inputLayerWeights4Edge = new double[weightsLength][];
        count = 0;
        while (count < weightsLength) {
          line = br.readLine();

          toks = line.split("\\t");
          int weights2Length = Integer.parseInt(toks[0]);
          inputLayerWeights4Edge[count] = new double[weights2Length];
          String[] weightsValue = toks[1].split(" ");
          if (weights2Length != weightsValue.length) {
            throw new RuntimeException("weights format error");
          }

          for (int i2 = 0; i2 < weights2Length; i2++) {
            inputLayerWeights4Edge[count][i2] = Double.parseDouble(weightsValue[i2]);
          }
          count++;
        }
        /***************************************/
        System.err.printf("DEBUG: double[%d][] inputLayerWeights4Edge loaded\n", weightsLength);
        /***************************************/

        line = br.readLine();

        toks = line.split("\\t");
        if (!toks[0].equals("outputLayerWeights4Edge.length=")) {
          throw new RuntimeException("format error");
        }
        weightsLength = Integer.parseInt(toks[1]);
        outputLayerWeights4Edge = new double[weightsLength][];
        count = 0;
        while (count < weightsLength) {
          line = br.readLine();

          toks = line.split("\\t");
          int weights2Length = Integer.parseInt(toks[0]);
          outputLayerWeights4Edge[count] = new double[weights2Length];
          String[] weightsValue = toks[1].split(" ");
          if (weights2Length != weightsValue.length) {
            throw new RuntimeException("weights format error");
          }

          for (int i2 = 0; i2 < weights2Length; i2++) {
            outputLayerWeights4Edge[count][i2] = Double.parseDouble(weightsValue[i2]);
          }
          count++;
        }
        /***************************************/
        System.err.printf("DEBUG: double[%d][] outputLayerWeights loaded\n", weightsLength);
        /***************************************/

      } else {
        line = br.readLine();

        toks = line.split("\\t");
        if (!toks[0].equals("linearWeights.length=")) {
          throw new RuntimeException("format error");
        }
        weightsLength = Integer.parseInt(toks[1]);
        linearWeights = new double[weightsLength][];
        count = 0;
        while (count < weightsLength) {
          line = br.readLine();

          toks = line.split("\\t");
          int weights2Length = Integer.parseInt(toks[0]);
          linearWeights[count] = new double[weights2Length];
          String[] weightsValue = toks[1].split(" ");
          if (weights2Length != weightsValue.length) {
            throw new RuntimeException("weights format error");
          }

          for (int i2 = 0; i2 < weights2Length; i2++) {
            linearWeights[count][i2] = Double.parseDouble(weightsValue[i2]);
          }
          count++;
        }
        /***************************************/
        System.err.printf("DEBUG: double[%d][] linearWeights loaded\n", weightsLength);
        /***************************************/
      }

      line = br.readLine();

      toks = line.split("\\t");
      if (!toks[0].equals("inputLayerWeights.length=")) {
        throw new RuntimeException("format error");
      }
      weightsLength = Integer.parseInt(toks[1]);
      inputLayerWeights = new double[weightsLength][];
      count = 0;
      while (count < weightsLength) {
        line = br.readLine();

        toks = line.split("\\t");
        int weights2Length = Integer.parseInt(toks[0]);
        inputLayerWeights[count] = new double[weights2Length];
        String[] weightsValue = toks[1].split(" ");
        if (weights2Length != weightsValue.length) {
          throw new RuntimeException("weights format error");
        }

        for (int i2 = 0; i2 < weights2Length; i2++) {
          inputLayerWeights[count][i2] = Double.parseDouble(weightsValue[i2]);
        }
        count++;
      }
      /***************************************/
      System.err.printf("DEBUG: double[%d][] inputLayerWeights loaded\n", weightsLength);
      /***************************************/

      line = br.readLine();

      toks = line.split("\\t");
      if (!toks[0].equals("outputLayerWeights.length=")) {
        throw new RuntimeException("format error");
      }
      weightsLength = Integer.parseInt(toks[1]);
      outputLayerWeights = new double[weightsLength][];
      count = 0;
      while (count < weightsLength) {
        line = br.readLine();

        toks = line.split("\\t");
        int weights2Length = Integer.parseInt(toks[0]);
        outputLayerWeights[count] = new double[weights2Length];
        String[] weightsValue = toks[1].split(" ");
        if (weights2Length != weightsValue.length) {
          throw new RuntimeException("weights format error");
        }

        for (int i2 = 0; i2 < weights2Length; i2++) {
          outputLayerWeights[count][i2] = Double.parseDouble(weightsValue[i2]);
        }
        count++;
      }
      /***************************************/
      System.err.printf("DEBUG: double[%d][] outputLayerWeights loaded\n", weightsLength);
      /***************************************/
    }

    // <featureFactory>
    // edu.stanford.nlp.wordseg.Gale2007ChineseSegmenterFeatureFactory
    // </featureFactory>
    line = br.readLine();

    String[] featureFactoryName = line.split(" ");
    if (!featureFactoryName[0].equals("<featureFactory>") || !featureFactoryName[2].equals("</featureFactory>")) {
      throw new RuntimeException("format error");
    }
    featureFactory = (edu.stanford.nlp.sequences.FeatureFactory<IN>) Class.forName(featureFactoryName[1]).newInstance();
    featureFactory.init(flags);

    reinit();

    // <windowSize> 2 </windowSize>
    line = br.readLine();

    String[] windowSizeName = line.split(" ");
    if (!windowSizeName[0].equals("<windowSize>") || !windowSizeName[2].equals("</windowSize>")) {
      throw new RuntimeException("format error");
    }
    windowSize = Integer.parseInt(windowSizeName[1]);

    // weights.length= 2655170
    line = br.readLine();

    toks = line.split("\\t");
    if (!toks[0].equals("weights.length=")) {
      throw new RuntimeException("format error");
    }
    int weightsLength = Integer.parseInt(toks[1]);
    weights = new double[weightsLength][];
    count = 0;
    while (count < weightsLength) {
      line = br.readLine();

      toks = line.split("\\t");
      int weights2Length = Integer.parseInt(toks[0]);
      weights[count] = new double[weights2Length];
      String[] weightsValue = toks[1].split(" ");
      if (weights2Length != weightsValue.length) {
        throw new RuntimeException("weights format error");
      }

      for (int i2 = 0; i2 < weights2Length; i2++) {
        weights[count][i2] = Double.parseDouble(weightsValue[i2]);
      }
      count++;
    }
    System.err.printf("DEBUG: double[%d][] weights loaded\n", weightsLength);
    line = br.readLine();

    if (line != null) {
      throw new RuntimeException("weights format error");
    }
  }

  /**
   * Serialize the model to a human readable format. It's not yet complete. It
   * should now work for Chinese segmenter though. TODO: check things in
   * serializeClassifier and add other necessary serialization back
   *
   * @param serializePath
   *          File to write text format of classifier to.
   */
  public void serializeTextClassifier(String serializePath) {
    System.err.print("Serializing Text classifier to " + serializePath + "...");
    try {
      PrintWriter pw = new PrintWriter(new GZIPOutputStream(new FileOutputStream(serializePath)));

      pw.printf("labelIndices.length=\t%d\n", labelIndices.length);
      for (int i = 0; i < labelIndices.length; i++) {
        pw.printf("labelIndices[%d].size()=\t%d\n", i, labelIndices[i].size());
        for (int j = 0; j < labelIndices[i].size(); j++) {
          int[] label = labelIndices[i].get(j).getLabel();
          List<Integer> list = new ArrayList<Integer>();
          for (int l : label) {
            list.add(l);
          }
          pw.printf("%d\t%s\n", j, StringUtils.join(list, " "));
        }
      }

      pw.printf("classIndex.size()=\t%d\n", classIndex.size());
      for (int i = 0; i < classIndex.size(); i++) {
        pw.printf("%d\t%s\n", i, classIndex.get(i));
      }
      // pw.printf("</classIndex>\n");

      pw.printf("featureIndex.size()=\t%d\n", featureIndex.size());
      for (int i = 0; i < featureIndex.size(); i++) {
        pw.printf("%d\t%s\n", i, featureIndex.get(i));
      }
      // pw.printf("</featureIndex>\n");

      pw.println("<flags>");
      pw.print(flags.toString());
      pw.println("</flags>");

      if (flags.nonLinearCRF) {
        pw.printf("nodeFeatureIndicesMap.size()=\t%d\n", nodeFeatureIndicesMap.size());
        for (int i = 0; i < nodeFeatureIndicesMap.size(); i++) {
          pw.printf("%d\t%d\n", i, nodeFeatureIndicesMap.get(i));
        }

        pw.printf("edgeFeatureIndicesMap.size()=\t%d\n", edgeFeatureIndicesMap.size());
        for (int i = 0; i < edgeFeatureIndicesMap.size(); i++) {
          pw.printf("%d\t%d\n", i, edgeFeatureIndicesMap.get(i));
        }

        if (flags.secondOrderNonLinear) {
          pw.printf("inputLayerWeights4Edge.length=\t%d\n", inputLayerWeights4Edge.length);
          for (double[] ws : inputLayerWeights4Edge) {
            ArrayList<Double> list = new ArrayList<Double>();
            for (double w : ws) {
              list.add(w);
            }
            pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
          }
          pw.printf("outputLayerWeights4Edge.length=\t%d\n", outputLayerWeights4Edge.length);
          for (double[] ws : outputLayerWeights4Edge) {
            ArrayList<Double> list = new ArrayList<Double>();
            for (double w : ws) {
              list.add(w);
            }
            pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
          }
        } else {
          pw.printf("linearWeights.length=\t%d\n", linearWeights.length);
          for (double[] ws : linearWeights) {
            ArrayList<Double> list = new ArrayList<Double>();
            for (double w : ws) {
              list.add(w);
            }
            pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
          }
        }
        pw.printf("inputLayerWeights.length=\t%d\n", inputLayerWeights.length);
        for (double[] ws : inputLayerWeights) {
          ArrayList<Double> list = new ArrayList<Double>();
          for (double w : ws) {
            list.add(w);
          }
          pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
        }
        pw.printf("outputLayerWeights.length=\t%d\n", outputLayerWeights.length);
        for (double[] ws : outputLayerWeights) {
          ArrayList<Double> list = new ArrayList<Double>();
          for (double w : ws) {
            list.add(w);
          }
          pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
        }
      }


      pw.printf("<featureFactory> %s </featureFactory>\n", featureFactory.getClass().getName());

      pw.printf("<windowSize> %d </windowSize>\n", windowSize);

      pw.printf("weights.length=\t%d\n", weights.length);
      for (double[] ws : weights) {
        ArrayList<Double> list = new ArrayList<Double>();
        for (double w : ws) {
          list.add(w);
        }
        pw.printf("%d\t%s\n", ws.length, StringUtils.join(list, " "));
      }

      pw.close();
      System.err.println("done.");

    } catch (Exception e) {
      System.err.println("Failed");
      e.printStackTrace();
      // don't actually exit in case they're testing too
      // System.exit(1);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void serializeClassifier(String serializePath) {
    System.err.print("Serializing classifier to " + serializePath + "...");

    ObjectOutputStream oos = null;
    try {
      oos = IOUtils.writeStreamFromString(serializePath);

      oos.writeObject(labelIndices);
      oos.writeObject(classIndex);
      oos.writeObject(featureIndex);
      oos.writeObject(flags);
      if (flags.nonLinearCRF) {
        oos.writeObject(nodeFeatureIndicesMap);
        oos.writeObject(edgeFeatureIndicesMap);
        if (flags.secondOrderNonLinear) {
          oos.writeObject(inputLayerWeights4Edge);
          oos.writeObject(outputLayerWeights4Edge);
        } else {
          oos.writeObject(linearWeights);
        }
        oos.writeObject(inputLayerWeights);
        oos.writeObject(outputLayerWeights);
      }
      oos.writeObject(featureFactory);
      oos.writeInt(windowSize);
      oos.writeObject(weights);
      // oos.writeObject(WordShapeClassifier.getKnownLowerCaseWords());

      oos.writeObject(knownLCWords);

      System.err.println("done.");

    } catch (Exception e) {
      System.err.println("Failed");
      e.printStackTrace();
      // don't actually exit in case they're testing too
      // System.exit(1);
    } finally {
      IOUtils.closeIgnoringExceptions(oos);
    }
  }

  /**
   * Loads a classifier from the specified InputStream. This version works
   * quietly (unless VERBOSE is true). If props is non-null then any properties
   * it specifies override those in the serialized file. However, only some
   * properties are sensible to change (you shouldn't change how features are
   * defined).
   * <p>
   * <i>Note:</i> This method does not close the ObjectInputStream. (But earlier
   * versions of the code used to, so beware....)
   */
  @Override
  @SuppressWarnings( { "unchecked" })
  // can't have right types in deserialization
  public void loadClassifier(ObjectInputStream ois, Properties props) throws ClassCastException, IOException,
      ClassNotFoundException {
    labelIndices = (Index<CRFLabel>[]) ois.readObject();
    classIndex = (Index<String>) ois.readObject();
    featureIndex = (Index<String>) ois.readObject();
    flags = (SeqClassifierFlags) ois.readObject();
    if (flags.nonLinearCRF) {
      nodeFeatureIndicesMap = (Index<Integer>) ois.readObject();
      edgeFeatureIndicesMap = (Index<Integer>) ois.readObject();
      if (flags.secondOrderNonLinear) {
        inputLayerWeights4Edge = (double[][]) ois.readObject();
        outputLayerWeights4Edge = (double[][]) ois.readObject();
      } else {
        linearWeights = (double[][]) ois.readObject();
      }
      inputLayerWeights = (double[][]) ois.readObject();
      outputLayerWeights = (double[][]) ois.readObject();
    }
    featureFactory = (edu.stanford.nlp.sequences.FeatureFactory) ois.readObject();

    if (props != null) {
      flags.setProperties(props, false);
    }
    reinit();

    windowSize = ois.readInt();
    weights = (double[][]) ois.readObject();

    // WordShapeClassifier.setKnownLowerCaseWords((Set) ois.readObject());
    knownLCWords = (Set<String>) ois.readObject();

    if (VERBOSE) {
      System.err.println("windowSize=" + windowSize);
      System.err.println("flags=\n" + flags);
    }
  }

  /**
   * This is used to load the default supplied classifier stored within the jar
   * file. THIS FUNCTION WILL ONLY WORK IF THE CODE WAS LOADED FROM A JAR FILE
   * WHICH HAS A SERIALIZED CLASSIFIER STORED INSIDE IT.
   */
  public void loadDefaultClassifier() {
    loadJarClassifier(DEFAULT_CLASSIFIER, null);
  }

  /**
   * This is used to load the default supplied classifier stored within the jar
   * file. THIS FUNCTION WILL ONLY WORK IF THE CODE WAS LOADED FROM A JAR FILE
   * WHICH HAS A SERIALIZED CLASSIFIER STORED INSIDE IT.
   */
  public void loadDefaultClassifier(Properties props) {
    loadJarClassifier(DEFAULT_CLASSIFIER, props);
  }

  /**
   * Used to get the default supplied classifier inside the jar file. THIS
   * FUNCTION WILL ONLY WORK IF THE CODE WAS LOADED FROM A JAR FILE WHICH HAS A
   * SERIALIZED CLASSIFIER STORED INSIDE IT.
   *
   * @return The default CRFClassifier in the jar file (if there is one)
   */
  public static <IN extends CoreMap> CRFClassifier<IN> getDefaultClassifier() {
    CRFClassifier<IN> crf = new CRFClassifier<IN>();
    crf.loadDefaultClassifier();
    return crf;
  }

  /**
   * Used to get the default supplied classifier inside the jar file. THIS
   * FUNCTION WILL ONLY WORK IF THE CODE WAS LOADED FROM A JAR FILE WHICH HAS A
   * SERIALIZED CLASSIFIER STORED INSIDE IT.
   *
   * @return The default CRFClassifier in the jar file (if there is one)
   */
  public static <IN extends CoreMap> CRFClassifier<IN> getDefaultClassifier(Properties props) {
    CRFClassifier<IN> crf = new CRFClassifier<IN>();
    crf.loadDefaultClassifier(props);
    return crf;
  }

  /**
   * Used to load a classifier stored as a resource inside a jar file. THIS
   * FUNCTION WILL ONLY WORK IF THE CODE WAS LOADED FROM A JAR FILE WHICH HAS A
   * SERIALIZED CLASSIFIER STORED INSIDE IT.
   *
   * @param resourceName
   *          Name of clasifier resource inside the jar file.
   * @return A CRFClassifier stored in the jar file
   */
  public static <IN extends CoreMap> CRFClassifier<IN> getJarClassifier(String resourceName, Properties props) {
    CRFClassifier<IN> crf = new CRFClassifier<IN>();
    crf.loadJarClassifier(resourceName, props);
    return crf;
  }

  /**
   * Loads a CRF classifier from a filepath, and returns it.
   *
   * @param file
   *          File to load classifier from
   * @return The CRF classifier
   *
   * @throws IOException
   *           If there are problems accessing the input stream
   * @throws ClassCastException
   *           If there are problems interpreting the serialized data
   * @throws ClassNotFoundException
   *           If there are problems interpreting the serialized data
   */
  public static <IN extends CoreMap> CRFClassifier<IN> getClassifier(File file) throws IOException, ClassCastException,
      ClassNotFoundException {
    CRFClassifier<IN> crf = new CRFClassifier<IN>();
    crf.loadClassifier(file);
    return crf;
  }

  /**
   * Loads a CRF classifier from an InputStream, and returns it. This method
   * does not buffer the InputStream, so you should have buffered it before
   * calling this method.
   *
   * @param in
   *          InputStream to load classifier from
   * @return The CRF classifier
   *
   * @throws IOException
   *           If there are problems accessing the input stream
   * @throws ClassCastException
   *           If there are problems interpreting the serialized data
   * @throws ClassNotFoundException
   *           If there are problems interpreting the serialized data
   */
  public static CRFClassifier<? extends CoreMap> getClassifier(InputStream in) throws IOException, ClassCastException,
      ClassNotFoundException {
    CRFClassifier<? extends CoreMap> crf = new CRFClassifier<CoreMap>();
    crf.loadClassifier(in);
    return crf;
  }

  public static CRFClassifier<CoreLabel> getClassifierNoExceptions(String loadPath) {
    CRFClassifier<CoreLabel> crf = new CRFClassifier<CoreLabel>();
    crf.loadClassifierNoExceptions(loadPath);
    return crf;
  }

  public static CRFClassifier<CoreLabel> getClassifier(String loadPath) throws IOException, ClassCastException,
      ClassNotFoundException {
    CRFClassifier<CoreLabel> crf = new CRFClassifier<CoreLabel>();
    crf.loadClassifier(loadPath);
    return crf;
  }

  public static CRFClassifier<? extends CoreMap> getClassifier(String loadPath, Properties props) throws IOException, ClassCastException,
      ClassNotFoundException {
    CRFClassifier<? extends CoreMap> crf = new CRFClassifier<CoreMap>();
    crf.loadClassifier(loadPath, props);
    return crf;
  }

  /** The main method. See the class documentation. */
  public static void main(String[] args) throws Exception {
    StringUtils.printErrInvocationString("CRFClassifier", args);

    Properties props = StringUtils.argsToProperties(args);
    CRFClassifier<CoreLabel> crf = new CRFClassifier<CoreLabel>(props);
    String testFile = crf.flags.testFile;
    String testFiles = crf.flags.testFiles;
    String textFile = crf.flags.textFile;
    String textFiles = crf.flags.textFiles;
    String loadPath = crf.flags.loadClassifier;
    String loadTextPath = crf.flags.loadTextClassifier;
    String serializeTo = crf.flags.serializeTo;
    String serializeToText = crf.flags.serializeToText;

    if (loadPath != null) {
      crf.loadClassifierNoExceptions(loadPath, props);
    } else if (loadTextPath != null) {
      System.err.println("Warning: this is now only tested for Chinese Segmenter");
      System.err.println("(Sun Dec 23 00:59:39 2007) (pichuan)");
      try {
        crf.loadTextClassifier(loadTextPath, props);
        // System.err.println("DEBUG: out from crf.loadTextClassifier");
      } catch (Exception e) {
        throw new RuntimeException("error loading " + loadTextPath, e);
      }
    } else if (crf.flags.loadJarClassifier != null) {
      crf.loadJarClassifier(crf.flags.loadJarClassifier, props);
    } else if (crf.flags.trainFile != null || crf.flags.trainFileList != null) {
      crf.train();
    } else {
      crf.loadDefaultClassifier();
    }

    // System.err.println("Using " + crf.flags.featureFactory);
    // System.err.println("Using " +
    // StringUtils.getShortClassName(crf.readerAndWriter));

    if (serializeTo != null) {
      crf.serializeClassifier(serializeTo);
    }

    if (serializeToText != null) {
      crf.serializeTextClassifier(serializeToText);
    }

    if (testFile != null) {
      DocumentReaderAndWriter<CoreLabel> readerAndWriter = crf.defaultReaderAndWriter();
      if (crf.flags.searchGraphPrefix != null) {
        crf.classifyAndWriteViterbiSearchGraph(testFile, crf.flags.searchGraphPrefix, crf.makeReaderAndWriter());
      } else if (crf.flags.printFirstOrderProbs) {
        crf.printFirstOrderProbs(testFile, readerAndWriter);
      } else if (crf.flags.printProbs) {
        crf.printProbs(testFile, readerAndWriter);
      } else if (crf.flags.useKBest) {
        int k = crf.flags.kBest;
        crf.classifyAndWriteAnswersKBest(testFile, k, readerAndWriter);
      } else if (crf.flags.printLabelValue) {
        crf.printLabelInformation(testFile, readerAndWriter);
      } else {
        crf.classifyAndWriteAnswers(testFile, readerAndWriter);
      }
    }

    if (testFiles != null) {
      List<File> files = new ArrayList<File>();
      for (String filename : testFiles.split(",")) {
        files.add(new File(filename));
      }
      crf.classifyAndWriteAnswers(files, crf.defaultReaderAndWriter());
    }

    if (textFile != null) {
      crf.classifyAndWriteAnswers(textFile);
    }

    if (textFiles != null) {
      List<File> files = new ArrayList<File>();
      for (String filename : textFiles.split(",")) {
        files.add(new File(filename));
      }
      crf.classifyAndWriteAnswers(files);
    }

    if (crf.flags.readStdin) {
      crf.classifyStdin();
    }
  } // end main

  @Override
  public List<IN> classifyWithGlobalInformation(List<IN> tokenSeq, final CoreMap doc, final CoreMap sent) {
    return classify(tokenSeq);
  }

  public void writeWeights(PrintStream p) {
    for (String feature : featureIndex) {
      int index = featureIndex.indexOf(feature);
      // line.add(feature+"["+(-p)+"]");
      // rowHeaders.add(feature + '[' + (-p) + ']');
      double[] v = weights[index];
      Index<CRFLabel> l = this.labelIndices[0];
      p.println(feature + "\t\t");
      for (CRFLabel label : l) {
        p.print(label.toString(classIndex) + ":" + v[l.indexOf(label)] + "\t");
      }
      p.println();

    }
  }

} // end class CRFClassifier
