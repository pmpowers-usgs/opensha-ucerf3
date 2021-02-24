package org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.ScalarValuePlausibiltyFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.coulomb.NetRuptureCoulombFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.path.CumulativeProbPathEvaluator;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.path.PathPlausibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.CoulombSectRatioProb;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.CumulativeProbabilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.RelativeCoulombProb;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RupSetDiagnosticsPageGen;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RupSetMapMaker;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCache;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator.AggregationMethod;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.PatchAlignment;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessType;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.inversion.laughTest.PlausibilityResult;

/**
 * This connection strategy uses one or more plausibility filters to determine the best jumping point between each pair
 * of fault subsection clusters.
 * 
 * If there are multiple viable connection points (i.e., less than the specified maximum distance and passing all filters),
 * then the passed in JumpSelector will be used to select the best version. The current preffered jump selector chooses
 * the jump that has at least one passing branch direction and the fewest failing branch directions. This encourages end-to-end
 * connections: if a simple branch point (end-to-end) works, it will use that. Ties are then broken according to distance,
 * unless a scalar filter is passed in and the distance difference is < 2km, in which case the scalar value is used.
 * 
 * @author kevin
 *
 */
public class PlausibleClusterConnectionStrategy extends ClusterConnectionStrategy {

	private SectionDistanceAzimuthCalculator distCalc;
	private double maxJumpDist;
	private JumpSelector selector;
	private List<PlausibilityFilter> filters;
	
	private ScalarValuePlausibiltyFilter<Double> scalarFilter;
	private Range<Double> scalarRange;
	
	public static interface JumpSelector {
		
		public CandidateJump getBest(List<CandidateJump> candidates, Range<Double> acceptableScalarRange, boolean verbose);
	}
	
	public static class MinDistanceSelector implements JumpSelector {

		@Override
		public CandidateJump getBest(List<CandidateJump> candidates, Range<Double> acceptableScalarRange, boolean verbose) {
			return getMinDist(candidates);
		}
		
	}
	
	private static CandidateJump getMinDist(Collection<CandidateJump> candidates) {
		if (candidates == null)
			return null;
		float minDist = Float.POSITIVE_INFINITY;
		CandidateJump best = null;
		
		for (CandidateJump candidate : candidates) {
			if ((float)candidate.distance < minDist) {
				minDist = (float)candidate.distance;
				best = candidate;
			}
		}
		
		return best;
	}
	
	public static class BestScalarSelector implements JumpSelector {
		
		private final double equivDistance;

		public BestScalarSelector(double equivDistance) {
			this.equivDistance = equivDistance;
		}

		@Override
		public CandidateJump getBest(List<CandidateJump> candidates, Range<Double> acceptableScalarRange, boolean verbose) {
			if (candidates == null || candidates.isEmpty())
				return null;
			Collections.sort(candidates, distCompare);
			if (equivDistance > 0d) {
				double maxDist = candidates.get(0).distance + equivDistance;
				List<CandidateJump> withinEquiv = new ArrayList<>();
				for (int i=0; i<candidates.size(); i++) {
					CandidateJump candidate = candidates.get(i);
					if ((float)candidate.distance <= (float)maxDist)
						withinEquiv.add(candidate);
					else
						break;
				}
				candidates = withinEquiv;
			}
			Double bestValue = null;
			List<CandidateJump> best = null;
			for (CandidateJump candidate : candidates) {
				if (candidate.bestScalar == null || candidate.allowedJumps.isEmpty())
					continue;
				if (verbose)
					System.out.println("Testing "+candidate);
				if (bestValue == null) {
					// nothing before, so better
					bestValue = candidate.bestScalar;
					best = Lists.newArrayList(candidate);
					if (verbose)
						System.out.println("\tkeeping as first");
				} else if (bestValue.floatValue() == candidate.bestScalar.floatValue()) {
					// equivalent
					best.add(candidate);
					if (verbose)
						System.out.println("\tadding (tie)");
				} else if (ScalarValuePlausibiltyFilter.isValueBetter(candidate.bestScalar, bestValue, acceptableScalarRange)) {
					// better
					bestValue = candidate.bestScalar;
					best = Lists.newArrayList(candidate);
					if (verbose)
						System.out.println("\treplacing as new best");
				} else if (verbose) {
					System.out.println("\t"+candidate.bestScalar+" is worse than "+bestValue);
					System.out.println("\t\tRange: "+acceptableScalarRange);
					System.out.println("\t\tcompare: "+candidate.bestScalar.compareTo(bestValue));
					System.out.println("\t\tcandidate dist: "+ScalarValuePlausibiltyFilter.distFromRange(candidate.bestScalar, acceptableScalarRange));
					System.out.println("\t\tprev dist: "+ScalarValuePlausibiltyFilter.distFromRange(candidate.bestScalar, acceptableScalarRange));
				}
			}
			if (best == null) {
				// fallback to shortest
				if (verbose)
					System.out.println("No scalars, falling back to minDist");
				return candidates.get(0);
			}
			return getMinDist(best);
		}
		
	}
	
	public static class AnyPassMinDistSelector implements JumpSelector {
		
		private JumpSelector fallback;

		public AnyPassMinDistSelector() {
			this(new MinDistanceSelector());
		}
		
		public AnyPassMinDistSelector(JumpSelector fallback) {
			this.fallback = fallback;
		}

		@Override
		public CandidateJump getBest(List<CandidateJump> candidates, Range<Double> acceptableScalarRange, boolean verbose) {
			List<CandidateJump> passed = new ArrayList<>();
			for (CandidateJump candidate : candidates)
				if (!candidate.allowedJumps.isEmpty())
					passed.add(candidate);
			if (passed.isEmpty())
				return fallback.getBest(candidates, acceptableScalarRange, verbose);
			return getMinDist(passed);
		}
		
	}
	
	public static class PassesMinimizeFailedSelector implements JumpSelector {
		
		private JumpSelector fallback;

		public PassesMinimizeFailedSelector() {
			this(new MinDistanceSelector());
		}
		
		public PassesMinimizeFailedSelector(JumpSelector fallback) {
			this.fallback = fallback;
		}

		@Override
		public CandidateJump getBest(List<CandidateJump> candidates, Range<Double> acceptableScalarRange, boolean verbose) {
			List<CandidateJump> options = null;
			for (CandidateJump candidate : candidates) {
				if (candidate.allowedJumps.isEmpty())
					// doesn't pass any direction, skip
					continue;
				if (options == null) {
					// this is the new best
					if (verbose)
						System.out.println("First real option: "+candidate);
					options = Lists.newArrayList(candidate);
				} else {
					// test it
					CandidateJump prev = options.get(0);
					// 0 if same number of failures, +1 if prev had more failures, -1 if prev had fewer failures
					int cmp = Integer.compare(prev.failedJumps.size(), candidate.failedJumps.size());
					if (verbose)
						System.out.println("Comparing: failCMP="+cmp+"\n\tprev: "+prev+"\n\tnew="+candidate);
					if (cmp == 0) {
						// same number of failures, new it's better if fewer jumps in total (at end(s))
						// 0 if same number of total jumps, +1 if prev had more jumps, -1 if prev had fewer jumps
						cmp = Integer.compare(prev.totalJumps, candidate.totalJumps);
						if (verbose)
							System.out.println("Fellback to totalJumps: jumpCMP="+cmp+"\tprev: "+prev.totalJumps+"\tnew="+candidate.totalJumps);
					}
					if (cmp > 0) {
						// this is the new best: fewer failures, or same failure and fewer branches
						if (verbose)
							System.out.println("\t\tnew best!");
						options = Lists.newArrayList(candidate);
					} else if (cmp == 0) {
						// tie
						if (verbose)
							System.out.println("\t\ttie for best!");
						options.add(candidate);
					}
				}
			}
			if (options == null)
				return fallback.getBest(candidates, acceptableScalarRange, verbose);
			if (verbose) {
				System.out.println("Ended with "+options.size()+" options:");
				for (CandidateJump candidate : options)
					System.out.println("\t"+candidate);
			}
			return fallback.getBest(options, acceptableScalarRange, verbose);
		}
		
	}
	
	public static final JumpSelector JUMP_SELECTOR_DEFAULT = new PassesMinimizeFailedSelector(new BestScalarSelector(2d));

	public PlausibleClusterConnectionStrategy(List<? extends FaultSection> subSects,
			SectionDistanceAzimuthCalculator distCalc, double maxJumpDist, PlausibilityFilter... filters) {
		this(subSects, distCalc, maxJumpDist, JUMP_SELECTOR_DEFAULT, filters);
	}

	public PlausibleClusterConnectionStrategy(List<? extends FaultSection> subSects,
			SectionDistanceAzimuthCalculator distCalc, double maxJumpDist, JumpSelector selector,
			PlausibilityFilter... filters) {
		this(subSects, distCalc, maxJumpDist, selector, Lists.newArrayList(filters));
	}

	public PlausibleClusterConnectionStrategy(List<? extends FaultSection> subSects,
			SectionDistanceAzimuthCalculator distCalc, double maxJumpDist, JumpSelector selector,
			List<PlausibilityFilter> filters) {
		super(subSects);
		Preconditions.checkState(!filters.isEmpty());
		this.maxJumpDist = maxJumpDist;
		this.distCalc = distCalc;
		this.filters = filters;
		this.selector = selector;
		for (PlausibilityFilter filter : filters) {
			if (filter instanceof ScalarValuePlausibiltyFilter<?>) {
				Range<?> range = ((ScalarValuePlausibiltyFilter<?>)filter).getAcceptableRange();
				if (range != null) {
					scalarFilter = new ScalarValuePlausibiltyFilter.DoubleWrapper((ScalarValuePlausibiltyFilter<?>)filter);
					scalarRange = scalarFilter.getAcceptableRange();
					break;
				}
			}
		}
	}

//	/**
//	 * @return the threshold between which two distances are thought to be equivalent. If candidate jumps are otherwise
//	 * equivalent and have scalar values associated, the jump with the better scalar value will be taken
//	 */
//	public double getEquivalentDistThreshold() {
//		return equivalentDistThreshold;
//	}
//
//	/**
//	 * Sets the threshold between which two distances are thought to be equivalent. If candidate jumps are otherwise
//	 * equivalent and have scalar values associated, the jump with the better scalar value will be taken
//	 * @param equivalentDistThreshold
//	 */
//	public void setEquivalentDistThreshold(double equivalentDistThreshold) {
//		this.equivalentDistThreshold = equivalentDistThreshold;
//	}
//
//	/**
//	 * @return the bonus associated with taking a jump at a section end point
//	 */
//	public int getEndPointBonus() {
//		return endPointBonus;
//	}
//
//	/**
//	 * Sets the end point bonus, used to lend extra weight to jumps that occur at an endpoint of one or more sections.
//	 * @param endPointBonus
//	 */
//	public void setEndPointBonus(int endPointBonus) {
//		this.endPointBonus = endPointBonus;
//	}

	private static final int debug_parent_1 = -1;
	private static final int debug_parent_2 = -1;
//	private static final int debug_parent_1 = 84;
//	private static final int debug_parent_2 = 85;
//	private static final int debug_parent_1 = 170;
//	private static final int debug_parent_2 = 97;
//	private static final int debug_parent_1 = 295;
//	private static final int debug_parent_2 = 170;
//	private static final int debug_parent_1 = 170;
//	private static final int debug_parent_2 = 96;
//	private static final int debug_parent_1 = 254;
//	private static final int debug_parent_2 = 209;
//	private static final int debug_parent_1 = 82;
//	private static final int debug_parent_2 = 84;

	@Override
	protected List<Jump> buildPossibleConnections(FaultSubsectionCluster from, FaultSubsectionCluster to) {
		return buildPossibleConnections(from, to,
				from.parentSectionID == debug_parent_1 && to.parentSectionID == debug_parent_2
				|| to.parentSectionID == debug_parent_1 && from.parentSectionID == debug_parent_2);
	}
	
	public static class CandidateJump {
		public final FaultSubsectionCluster fromCluster;
		public final FaultSection fromSection;
		public final boolean fromEnd;
		public final FaultSubsectionCluster toCluster;
		public final FaultSection toSection;
		public final boolean toEnd;
		public final double distance;
		
		public final List<Jump> allowedJumps;
		public final List<Jump> failedJumps;
		public final Map<Jump, Double> jumpScalars;
		public final Double bestScalar;
		public final int totalJumps;
		
		public CandidateJump(FaultSubsectionCluster fromCluster, FaultSection fromSection, boolean fromEnd,
				FaultSubsectionCluster toCluster, FaultSection toSection, boolean toEnd, double distance,
				List<Jump> allowedJumps, List<Jump> failedJumps, Map<Jump, Double> jumpScalars, Double bestScalar) {
			super();
			this.fromCluster = fromCluster;
			this.fromSection = fromSection;
			this.fromEnd = fromEnd;
			this.toCluster = toCluster;
			this.toSection = toSection;
			this.toEnd = toEnd;
			this.distance = distance;
			this.allowedJumps = allowedJumps;
			this.failedJumps = failedJumps;
			this.jumpScalars = jumpScalars;
			this.bestScalar = bestScalar;
			this.totalJumps = allowedJumps.size() + failedJumps.size();
		}
		
		@Override
		public String toString() {
			String ret = fromSection.getSectionId()+"";
			if (fromEnd)
				ret += "[end]";
			ret += "->"+toSection.getSectionId();
			if (toEnd)
				ret += "[end]";
			ret += ": dist="+(float)distance+"\t"+allowedJumps.size()+"/"+(allowedJumps.size()+failedJumps.size())+" pass";
			if (bestScalar != null)
				ret += "\tbestScalar: "+bestScalar.floatValue();
			return ret;
		}
	}

	protected List<Jump> buildPossibleConnections(FaultSubsectionCluster from, FaultSubsectionCluster to, final boolean debug) {
		List<CandidateJump> candidates = new ArrayList<>();
		for (int i=0; i<from.subSects.size(); i++) {
			FaultSection s1 = from.subSects.get(i);
			List<List<? extends FaultSection>> fromStrands = getStrandsTo(from.subSects, i);
			boolean fromEnd = i == 0 || i == from.subSects.size()-1;
			for (int j=0; j<to.subSects.size(); j++) {
				FaultSection s2 = to.subSects.get(j);
				double dist = distCalc.getDistance(s1, s2);
				boolean toEnd = j == 0 || j == to.subSects.size()-1;
				if ((float)dist <= (float)maxJumpDist) {
					if (debug)
						System.out.println(s1.getSectionId()+" => "+s2.getSectionId()+": "+dist+" km");
					List<Jump> allowedJumps = new ArrayList<>();
					List<Jump> failedJumps = new ArrayList<>();
					Map<Jump, Double> jumpScalars = scalarFilter == null ? null : new HashMap<>();
					Double bestScalar = null;
					List<List<? extends FaultSection>> toStrands = getStrandsTo(to.subSects, j);
					for (List<? extends FaultSection> fromStrand : fromStrands) {
						FaultSubsectionCluster fromCluster = new FaultSubsectionCluster(fromStrand);
						if (!fromCluster.subSects.get(fromCluster.subSects.size()-1).equals(s1))
							// reverse the 'from' such that it leads up to the jump point
							fromCluster = fromCluster.reversed();
						for (List<? extends FaultSection> toStrand : toStrands) {
							FaultSubsectionCluster toCluster = new FaultSubsectionCluster(toStrand);
							if (!toCluster.subSects.get(0).equals(s2))
								// reverse the 'to' such that it starts from the jump point
								toCluster = toCluster.reversed();
							Jump testJump = new Jump(s1, fromCluster, s2, toCluster, dist);
							ClusterRupture rupture = new ClusterRupture(fromCluster).take(testJump);
							if (debug)
								System.out.println("\tTrying rupture: "+rupture);
							PlausibilityResult result = PlausibilityResult.PASS;
							boolean directional = false;
							for (PlausibilityFilter filter : filters) {
								result = result.logicalAnd(filter.apply(rupture, false));
								directional = directional || filter.isDirectional(false);
							}
							if (debug)
								System.out.println("\tResult: "+result);
							Double myScalar = null;
							if (scalarFilter != null && result.isPass()) {
								// get scalar value
								myScalar = scalarFilter.getValue(rupture); 
								if (debug)
									System.out.println("\tScalar val: "+myScalar);
							}
							if (directional && (scalarFilter != null || !result.isPass())) {
								// try the other direction
								rupture = rupture.reversed();
								if (debug)
									System.out.println("\tTrying reversed: "+rupture);
								PlausibilityResult reverseResult = PlausibilityResult.PASS;
								for (PlausibilityFilter filter : filters)
									reverseResult = result.logicalAnd(filter.apply(rupture, false));
								if (debug)
									System.out.println("\tResult: "+reverseResult);
								if (scalarFilter != null && reverseResult.isPass()) {
									// get scalar value
									Double myScalar2 = scalarFilter.getValue(rupture); 
									if (debug)
										System.out.println("\tScalar val: "+myScalar2);
									if (myScalar == null || (myScalar2 != null &&
											ScalarValuePlausibiltyFilter.isValueBetter(myScalar2, myScalar, scalarRange)))
										myScalar = myScalar2;
								}
								result = result.logicalOr(reverseResult);
							}
							if (myScalar != null) {
								jumpScalars.put(testJump, myScalar);
								if (bestScalar == null || ScalarValuePlausibiltyFilter.isValueBetter(myScalar, bestScalar, scalarRange))
									bestScalar = myScalar;
							}
							
							if (result.isPass())
								allowedJumps.add(testJump);
							else
								failedJumps.add(testJump);
						}
					}
					CandidateJump candidate = new CandidateJump(from, s1, fromEnd, to, s2, toEnd, dist,
							allowedJumps, failedJumps, jumpScalars, bestScalar);
					if (debug)
						System.out.println("New candidate: "+candidate);
					candidates.add(candidate);
				}
			}
		}
		if (candidates.isEmpty())
			return null;
		if (debug) {
			System.out.println("All candidates (distance sorted)");
			Collections.sort(candidates, distCompare);
			for (CandidateJump candidate : candidates)
				System.out.println("\t"+candidate);
		}
		CandidateJump bestCandidate = selector.getBest(candidates, scalarRange, debug);
		if (debug)
			System.out.println("Final candidate: "+bestCandidate);
		if (bestCandidate == null)
			return null;
		return Lists.newArrayList(new Jump(
				bestCandidate.fromSection, from, bestCandidate.toSection, to, bestCandidate.distance));
	}
	
	private static final Comparator<CandidateJump> distCompare = new Comparator<CandidateJump>() {

		@Override
		public int compare(CandidateJump o1, CandidateJump o2) {
			return Double.compare(o1.distance, o2.distance);
		}
	};
	
	private List<List<? extends FaultSection>> getStrandsTo(List<? extends FaultSection> subSects, int index) {
		Preconditions.checkState(!subSects.isEmpty());
		List<List<? extends FaultSection>> ret = new ArrayList<>();
		if (subSects.size() == 1) {
			ret.add(subSects.subList(index, index+1));
		} else {
			if (index > 0)
				ret.add(subSects.subList(0, index+1));
			if (index < subSects.size()-1)
				ret.add(subSects.subList(index, subSects.size()));
		}
		return ret;
	}

	@Override
	public String getName() {
		if (filters.size() == 1)
			return filters.get(0)+" Plausibile: maxDist="+new DecimalFormat("0.#").format(maxJumpDist)+" km";
		return "Plausible ("+filters.size()+" filters): maxDist="+new DecimalFormat("0.#").format(maxJumpDist)+" km";
	}

	@Override
	public double getMaxJumpDist() {
		return maxJumpDist;
	}
	
	public static void main(String[] args) throws IOException {
		List<? extends FaultSection> subSects = DeformationModels.loadSubSects(FaultModels.FM3_1, DeformationModels.GEOLOGIC);
		SubSectStiffnessCalculator stiffnessCalc = new SubSectStiffnessCalculator(
				subSects, 2d, 3e4, 3e4, 0.5, PatchAlignment.FILL_OVERLAP, 1d);
		AggregatedStiffnessCache stiffnessCache = stiffnessCalc.getAggregationCache(StiffnessType.CFF);
		File rupSetsDir = new File("/home/kevin/OpenSHA/UCERF4/rup_sets/");
		File stiffnessCacheFile = new File(rupSetsDir, stiffnessCache.getCacheFileName());
		int stiffnessCacheSize = 0;
		if (stiffnessCacheFile.exists())
			stiffnessCacheSize = stiffnessCache.loadCacheFile(stiffnessCacheFile);
		// common aggregators
		AggregatedStiffnessCalculator sumAgg = new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM);
		AggregatedStiffnessCalculator fractIntsAgg = new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.FLATTEN, AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.NORM_BY_COUNT);
		
		SectionDistanceAzimuthCalculator distAzCalc = new SectionDistanceAzimuthCalculator(subSects);
		File distAzCacheFile = new File(rupSetsDir, "fm3_1_dist_az_cache.csv");
		if (distAzCacheFile.exists()) {
			System.out.println("Loading dist/az cache from "+distAzCacheFile.getAbsolutePath());
			distAzCalc.loadCacheFile(distAzCacheFile);
		}
		
		double maxJumpDist = 10d;
		boolean favJump = true;
		
		float cffProb = 0.05f;
		RelativeCoulombProb cffProbCalc = new RelativeCoulombProb(
				sumAgg, new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, 0.1d), false, true, favJump, (float)maxJumpDist, distAzCalc);
		float cffRatio = 0.5f;
		CoulombSectRatioProb sectRatioCalc = new CoulombSectRatioProb(sumAgg, 2, favJump, (float)maxJumpDist, distAzCalc);
		NetRuptureCoulombFilter fractInts = new NetRuptureCoulombFilter(fractIntsAgg, Range.greaterThan(0.67f));
		PathPlausibilityFilter combinedPathFilter = new PathPlausibilityFilter(
				new CumulativeProbPathEvaluator(cffRatio, PlausibilityResult.FAIL_HARD_STOP, sectRatioCalc),
				new CumulativeProbPathEvaluator(cffProb, PlausibilityResult.FAIL_HARD_STOP, cffProbCalc));
		
		
		ClusterConnectionStrategy orig = new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist);
		String origName = "Min Distance";
//		ClusterConnectionStrategy orig = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist, sumAgg, false);
//		PlausibleClusterConnectionStrategy orig = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist,
//				new CumulativeProbabilityFilter(cffRatio, sectRatioCalc), threeQuarters);
//		PlausibleClusterConnectionStrategy orig = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist,
//				new CumulativeProbabilityFilter(cffRatio, sectRatioCalc), combinedPathFilter, threeQuarters);
//		String origName = "10km";
//		String origName = "No CFF Prob";
//				new CumulativeProbabilityFilter(cffRatio, sectRatioCalc), threeQuarters);
//		ClusterConnectionStrategy orig = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist,
//				new PassesMinimizeFailedSelector(),
//				new CumulativeProbabilityFilter(cffRatio, sectRatioCalc), combinedPathFilter, threeQuarters);
//		String origName = "Min Dist Fallback";
//		ClusterConnectionStrategy orig = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist,
//				new PassesMinimizeFailedSelector(new BestScalarSelector(2d)),
//				new CumulativeProbabilityFilter(cffRatio, new CoulombSectRatioProb(sumAgg, 2)), new PathPlausibilityFilter(
//						new CumulativeProbPathEvaluator(cffRatio, PlausibilityResult.FAIL_HARD_STOP, new CoulombSectRatioProb(sumAgg, 2)),
//						new CumulativeProbPathEvaluator(cffProb, PlausibilityResult.FAIL_HARD_STOP, new RelativeCoulombProb(
//								sumAgg, new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, 0.1d), false, true))), threeQuarters);
//		String origName = "No Fav Nump";
		
//		PlausibilityFilter filter = new CumulativeProbabilityFilter(0.5f, new CoulombSectRatioProb(sumAgg, 2));
		ClusterConnectionStrategy newStrat = new PlausibleClusterConnectionStrategy(subSects, distAzCalc, maxJumpDist,
				new PassesMinimizeFailedSelector(new BestScalarSelector(2d)),
				new CumulativeProbabilityFilter(cffRatio, sectRatioCalc), combinedPathFilter, fractInts);
		String newName = "New Plausible";
//		String newName = "15km";
		
		int threads = Integer.max(1, Integer.min(31, Runtime.getRuntime().availableProcessors()-2));
		orig.checkBuildThreaded(threads);
		newStrat.checkBuildThreaded(threads);
		
		HashSet<Jump> origJumps = new HashSet<>();
		for (FaultSubsectionCluster cluster : orig.getClusters())
			for (Jump jump : cluster.getConnections())
				if (jump.fromSection.getSectionId() < jump.toSection.getSectionId())
					origJumps.add(jump);
		HashSet<Jump> cffJumps = new HashSet<>();
		for (FaultSubsectionCluster cluster : newStrat.getClusters())
			for (Jump jump : cluster.getConnections())
				if (jump.fromSection.getSectionId() < jump.toSection.getSectionId())
					cffJumps.add(jump);
		HashSet<Jump> origUniqueJumps = new HashSet<>();
		HashSet<Jump> cffUniqueJumps = new HashSet<>();
		HashSet<Jump> commonJumps = new HashSet<>();
		for (Jump jump : origJumps) {
			if (cffJumps.contains(jump))
				commonJumps.add(jump);
			else
				origUniqueJumps.add(jump);
		}
		for (Jump jump : cffJumps) {
			if (!commonJumps.contains(jump))
				cffUniqueJumps.add(jump);
		}
		
		RupSetMapMaker mapMaker = new RupSetMapMaker(subSects, RupSetMapMaker.buildBufferedRegion(subSects));
		mapMaker.plotJumps(commonJumps, RupSetDiagnosticsPageGen.darkerTrans(Color.GREEN), "Common Jumps");
		mapMaker.plotJumps(origUniqueJumps, RupSetDiagnosticsPageGen.darkerTrans(Color.BLUE), origName);
		mapMaker.plotJumps(cffUniqueJumps, RupSetDiagnosticsPageGen.darkerTrans(Color.RED), newName);
		mapMaker.plot(new File("/tmp"), "cff_jumps_compare", "CFF Jump Comparison", 5000);
	}
	
	

}
