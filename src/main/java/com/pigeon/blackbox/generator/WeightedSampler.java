package com.pigeon.blackbox.generator;

import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/* Draws a value with a probability proportional to its weight, in O(log n) */
final class WeightedSampler<T> {

	private final List<T> values;

	/* cumulative[i] = sum of the weights of values 0 to i */
	private final double[] cumulative;

	WeightedSampler(List<T> values, double[] weights) {
		if (values.isEmpty() || values.size() != weights.length) {
			throw new IllegalArgumentException("one weight per value is required, got " + values.size()
					+ " values and " + weights.length + " weights");
		}
		this.values = List.copyOf(values);
		this.cumulative = new double[weights.length];
		double sum = 0;
		for (int i = 0; i < weights.length; i++) {
			if (weights[i] < 0) {
				throw new IllegalArgumentException("weights cannot be negative, got: " + weights[i]);
			}
			sum += weights[i];
			cumulative[i] = sum;
		}
		if (sum <= 0) {
			throw new IllegalArgumentException("at least one weight must be positive");
		}
	}

	static <T> WeightedSampler<T> of(List<T> values, ToDoubleFunction<? super T> weight) {
		double[] weights = new double[values.size()];
		for (int i = 0; i < weights.length; i++) {
			weights[i] = weight.applyAsDouble(values.get(i));
		}
		return new WeightedSampler<>(values, weights);
	}

	/* Weights are given in the declaration order of the enum constants */
	static <E extends Enum<E>> WeightedSampler<E> ofEnum(Class<E> type, double... weights) {
		return new WeightedSampler<>(List.of(type.getEnumConstants()), weights);
	}

	T next(RandomGenerator random) {
		return nextAmongFirst(values.size(), random);
	}

	/* Only the first `count` values can be drawn, e.g. the users already signed up */
	T nextAmongFirst(int count, RandomGenerator random) {
		if (count < 1 || count > values.size()) {
			throw new IllegalArgumentException("count must be between 1 and " + values.size() + ", got: " + count);
		}
		double target = random.nextDouble() * cumulative[count - 1];

		/* Binary search of the first index whose cumulative weight exceeds the target */
		int low = 0;
		int high = count - 1;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (cumulative[middle] > target) {
				high = middle;
			}
			else {
				low = middle + 1;
			}
		}
		return values.get(low);
	}

	double totalWeightOfFirst(int count) {
		return count == 0 ? 0 : cumulative[count - 1];
	}
}
