package mltk.predictor.gam.interaction;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mltk.cmdline.Argument;
import mltk.cmdline.CmdLineParser;
import mltk.core.Attribute;
import mltk.core.BinnedAttribute;
import mltk.core.Instance;
import mltk.core.Instances;
import mltk.core.NominalAttribute;
import mltk.core.Attribute.Type;
import mltk.core.io.InstancesReader;
import mltk.core.processor.Discretizer;
import mltk.predictor.function.CHistogram;
import mltk.predictor.function.Histogram2D;
import mltk.util.Element;
import mltk.util.MathUtils;
import mltk.util.tuple.IntPair;

/**
 * Class for fast interaction detection.
 * 
 * <p>
 * Reference:<br>
 * Y. Lou, R. Caruana, J. Gehrke, and G. Hooker. Accurate intelligible models with pairwise interactions. In
 * <i>Proceedings of the 19th ACM SIGKDD International Conference on Knowledge Discovery and Data Mining (KDD)</i>,
 * Chicago, IL, USA, 2013.
 * </p>
 * 
 * @author Yin Lou
 * 
 */
public class FAST {

	static class FASTThread extends Thread {

		List<Element<IntPair>> pairs;
		Instances instances;

		FASTThread(Instances instances) {
			this.instances = instances;
			this.pairs = new ArrayList<>();
		}

		public void add(Element<IntPair> pair) {
			pairs.add(pair);
		}

		public void run() {
			FAST.computeWeights(instances, pairs);
		}
	}

	static class Options {

		@Argument(name = "-r", description = "attribute file path")
		String attPath = null;

		@Argument(name = "-d", description = "dataset path", required = true)
		String datasetPath = null;

		@Argument(name = "-R", description = "residual path", required = true)
		String residualPath = null;

		@Argument(name = "-o", description = "output path", required = true)
		String outputPath = null;

		@Argument(name = "-b", description = "number of bins (default: 256)")
		int maxNumBins = 256;

		@Argument(name = "-p", description = "number of threads (default: 1)")
		int numThreads = 1;

	}

	/**
	 * Ranks pairwise interactions using FAST.
	 * 
	 * <pre>
	 * Usage: mltk.predictor.gam.interaction.FAST
	 * -d	dataset path
	 * -R	residual path
	 * -o	output path
	 * [-r]	attribute file path
	 * [-b]	number of bins (default: 256)
	 * [-p]	number of threads (default: 1)
	 * </pre>
	 * 
	 * @param args the command line arguments
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		Options opts = new Options();
		CmdLineParser parser = new CmdLineParser(FAST.class, opts);
		try {
			parser.parse(args);
		} catch (IllegalArgumentException e) {
			parser.printUsage();
			System.exit(1);
		}

		Instances instances = InstancesReader.read(opts.attPath, opts.datasetPath);

		System.out.println("Reading residuals...");
		BufferedReader br = new BufferedReader(new FileReader(opts.residualPath), 65535);
		for (int i = 0; i < instances.size(); i++) {
			String line = br.readLine();
			double residual = Double.parseDouble(line);
			Instance instance = instances.get(i);
			instance.setTarget(residual);
		}
		br.close();

		List<Attribute> attributes = instances.getAttributes();

		System.out.println("Discretizing attribute...");
		for (int i = 0; i < attributes.size(); i++) {
			if (attributes.get(i).getType() == Type.NUMERIC) {
				Discretizer.discretize(instances, i, opts.maxNumBins);
			}
		}

		System.out.println("Generating all pairs of attributes...");
		List<Element<IntPair>> pairs = new ArrayList<>();
		for (int i = 0; i < attributes.size(); i++) {
			for (int j = i + 1; j < attributes.size(); j++) {
				pairs.add(new Element<IntPair>(new IntPair(i, j), 0.0));
			}
		}

		System.out.println("Creating threads...");
		FASTThread[] threads = new FASTThread[opts.numThreads];
		long start = System.currentTimeMillis();
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new FASTThread(instances);
		}
		for (int i = 0; i < pairs.size(); i++) {
			threads[i % threads.length].add(pairs.get(i));
		}
		for (int i = 0; i < threads.length; i++) {
			threads[i].start();
		}
		System.out.println("Running FAST...");
		for (int i = 0; i < threads.length; i++) {
			threads[i].join();
		}
		long end = System.currentTimeMillis();
		System.out.println("Sorting pairs...");
		Collections.sort(pairs);

		System.out.println("Time: " + (end - start) / 1000.0);

		PrintWriter out = new PrintWriter(opts.outputPath);
		for (int i = 0; i < pairs.size(); i++) {
			Element<IntPair> pair = pairs.get(i);
			out.println(pair.element.v1 + "\t" + pair.element.v2 + "\t" + pair.weight);
		}
		out.flush();
		out.close();

	}

	/**
	 * Computes the weights of pairwise interactions.
	 * 
	 * @param instances the training set.
	 * @param pairs the list of pairs to compute.
	 */
	public static void computeWeights(Instances instances, List<Element<IntPair>> pairs) {
		List<Attribute> attributes = instances.getAttributes();
		boolean[] used = new boolean[attributes.size()];
		for (Element<IntPair> pair : pairs) {
			int f1 = pair.element.v1;
			int f2 = pair.element.v2;
			used[f1] = used[f2] = true;
		}
		CHistogram[] cHist = new CHistogram[attributes.size()];
		for (int i = 0; i < cHist.length; i++) {
			if (used[i]) {
				switch (attributes.get(i).getType()) {
					case BINNED:
						BinnedAttribute binnedAtt = (BinnedAttribute) attributes.get(i);
						cHist[i] = new CHistogram(binnedAtt.getNumBins());
						break;
					case NOMINAL:
						NominalAttribute nominalAtt = (NominalAttribute) attributes.get(i);
						cHist[i] = new CHistogram(nominalAtt.getCardinality());
					default:
						break;
				}
			}
		}
		double ySq = computeCHistograms(instances, used, cHist);
		for (Element<IntPair> pair : pairs) {
			final int f1 = pair.element.v1;
			final int f2 = pair.element.v2;
			final int size1 = cHist[f1].size();
			final int size2 = cHist[f2].size();
			Histogram2D hist2d = new Histogram2D(size1, size2);
			Histogram2D.computeHistogram2D(instances, f1, f2, hist2d);
			computeWeight(pair, cHist, hist2d, ySq);
		}
	}

	protected static double computeCHistograms(Instances instances, boolean[] used, CHistogram[] cHist) {
		double ySq = 0;
		// compute histogram
		for (Instance instance : instances) {
			double resp = instance.getTarget();
			for (int j = 0; j < instances.getAttributes().size(); j++) {
				if (used[j]) {
					if (!instance.isMissing(j)) {
						int idx = (int) instance.getValue(j);
						cHist[j].sum[idx] += resp * instance.getWeight();
						cHist[j].count[idx] += instance.getWeight();
					} else {
						cHist[j].sumOnMV += resp * instance.getWeight();
						cHist[j].countOnMV += instance.getWeight();
					}
				}
			}
			ySq += resp * resp * instance.getWeight();
		}
		// compute cumulative histogram
		for (int j = 0; j < cHist.length; j++) {
			if (used[j]) {
				for (int idx = 1; idx < cHist[j].size(); idx++) {
					cHist[j].sum[idx] += cHist[j].sum[idx - 1];
					cHist[j].count[idx] += cHist[j].count[idx - 1];
				}
			}
		}
		return ySq;
	}

	protected static void computeWeight(Element<IntPair> pair, CHistogram[] cHist, Histogram2D hist2d, double ySq) {
		final int f1 = pair.element.v1;
		final int f2 = pair.element.v2;
		final int size1 = cHist[f1].size();
		final int size2 = cHist[f2].size();
		Histogram2D.Table table = Histogram2D.computeTable(hist2d, cHist[f1], cHist[f2]);
		double bestRSS = Double.POSITIVE_INFINITY;
		double[] predInt = new double[4];
		double[] predOnMV1 = new double[2];
		double[] predOnMV2 = new double[2];
		double predOnMV12 = MathUtils.divide(hist2d.respOnMV12, hist2d.countOnMV12, 0);
		for (int v1 = 0; v1 < size1 - 1; v1++) {
			for (int v2 = 0; v2 < size2 - 1; v2++) {
				getPredictor(table, v1, v2, predInt, predOnMV1, predOnMV2);
				double rss = getRSS(table, v1, v2, ySq, predInt, predOnMV1, predOnMV2, predOnMV12);
				if (rss < bestRSS) {
					bestRSS = rss;
				}
			}
		}
		pair.weight = bestRSS;
	}

	protected static void getPredictor(Histogram2D.Table table, int v1, int v2,
			double[] pred, double[] predOnMV1, double[] predOnMV2) {
		double[] count = table.count[v1][v2];
		double[] resp = table.resp[v1][v2];
		for (int i = 0; i < pred.length; i++) {
			pred[i] = MathUtils.divide(resp[i], count[i], 0);
		}
		for (int i = 0; i < predOnMV1.length; i++) {
			predOnMV1[i] = MathUtils.divide(table.respOnMV1[v2][i], table.countOnMV1[v2][i], 0);
		}
		for (int i = 0; i < predOnMV2.length; i++) {
			predOnMV2[i] = MathUtils. divide(table.respOnMV2[v1][i], table.countOnMV2[v1][i], 0);
		}
	}

	protected static double getRSS(Histogram2D.Table table, int v1, int v2, double ySq,
			double[] pred, double[] predOnMV1, double[] predOnMV2, double predOnMV12) {
		double[] count = table.count[v1][v2];
		double[] resp = table.resp[v1][v2];
		double[] respOnMV1 = table.respOnMV1[v2];
		double[] countOnMV1 = table.countOnMV1[v2];
		double[] respOnMV2 = table.respOnMV2[v1];
		double[] countOnMV2 = table.countOnMV2[v1];
		double rss = ySq;
		// Compute main area
		double t = 0;
		for (int i = 0; i < pred.length; i++) {
			t += pred[i] * pred[i] * count[i];
		}
		rss += t;
		t = 0;
		for (int i = 0; i < pred.length; i++) {
			t += pred[i] * resp[i];
		}
		rss -= 2 * t;
		// Compute on mv1
		t = 0;
		for (int i = 0; i < predOnMV1.length; i++) {
			t += predOnMV1[i] * predOnMV1[i] * countOnMV1[i];
		}
		rss += t;
		t = 0;
		for (int i = 0; i < predOnMV1.length; i++) {
			t += predOnMV1[i] * respOnMV1[i];
		}
		rss -= 2 * t;
		// Compute on mv2
		t = 0;
		for (int i = 0; i < predOnMV2.length; i++) {
			t += predOnMV2[i] * predOnMV2[i] * countOnMV2[i];
		}
		rss += t;
		t = 0;
		for (int i = 0; i < predOnMV2.length; i++) {
			t += predOnMV2[i] * respOnMV2[i];
		}
		rss -= 2 * t;
		return rss;
	}

}
