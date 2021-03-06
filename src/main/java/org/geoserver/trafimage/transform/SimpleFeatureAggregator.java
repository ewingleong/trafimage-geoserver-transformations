/* Copyright (c) 2014 geOps - www.geops.de. All rights reserved.
 *
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.trafimage.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.logging.Logger;

import org.geoserver.trafimage.transform.util.MeasuredSimpleFeatureIterator;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;

/**
 * 
 * @author nico
 *
 */
public class SimpleFeatureAggregator {

	/**
	 * Statisics on a performed aggregation
	 * 
	 * @author nico
	 *
	 */
	public class AggregationStatistics {
		
		/**
		 * the minimum number of features in an aggregate
		 */
		public int numMaxEntriesInAggregate = 0;
		
		/**
		 * the number of aggregeted features created
		 */
		public int numAggregates = 0;
		
		/**
		 * the number of input features
		 */
		public int numInputFeatures = 0;
	}
	
	private ArrayList<String> aggregationColumns;
	
	private static final Logger LOGGER = Logging.getLogger(SimpleFeatureAggregator.class);
	private AggregationStatistics lastStatistics = null;
	private boolean measuringEnabled = false;
	
	
	public SimpleFeatureAggregator(final ArrayList<String> aggregationColumns) {
		this.aggregationColumns = aggregationColumns;
	}
	
	/**
	 * enable profiling and logging of time durations
	 * 
	 * @param enabled
	 */
	public void setMeasuringEnabled(boolean enabled) {
		this.measuringEnabled = enabled;
	}
	
	/**
	 * 
	 * @param collection
	 * @param aggregateAttributeName
	 * @return
	 */
	public SimpleFeatureCollection aggregate(final SimpleFeatureCollection collection, final String aggregateAttributeName) {
		final SimpleFeatureType inputSchema = collection.getSchema();
		final SimpleFeatureHasher hasher = new SimpleFeatureHasher();
		hasher.setMeasuringEnabled(this.measuringEnabled);
		hasher.setIncludeGeometry(true);
		
		this.lastStatistics = new AggregationStatistics();
		this.lastStatistics.numInputFeatures = collection.size();
		
		// process the attributes string into a set to eliminate duplicates
		for (final String attributeName: this.aggregationColumns) {
			if (inputSchema.getDescriptor(attributeName) != null) {
				hasher.addIncludedAttribute(attributeName);
			}
		}

		// build the new featuretype for the output geometries
		final HashSet<String> attributesSet = hasher.getIncludedAttributes();
		final SimpleFeatureType outputSchema = this.buildOutputFeatureType(inputSchema, attributesSet, aggregateAttributeName);
		
		// aggregate the features
		final MeasuredSimpleFeatureIterator featureIt = new MeasuredSimpleFeatureIterator(collection.features());
		featureIt.setMeasuringEnabled(this.measuringEnabled);
		final HashMap<Integer, SimpleFeature> featureMap = new HashMap<Integer,SimpleFeature>();
		final SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(outputSchema);
		try {
			while (featureIt.hasNext()) {
				final SimpleFeature feature = featureIt.next();
				final int hash = hasher.getHash(feature);
				
				if (!featureMap.containsKey(hash)) {
					// idx =0 is always the geometry. same order as during the creation of the SimpleFeatureBuilder
					final Object geometry = feature.getDefaultGeometry();
					featureBuilder.set(0, geometry);
					
					final Iterator<String> attributesSetIt2 = attributesSet.iterator();
					while(attributesSetIt2.hasNext()) {
						final String attributeName =  attributesSetIt2.next();
						final Object attributeValue = feature.getAttribute(attributeName);
						featureBuilder.set(attributeName, attributeValue);
					}
					featureBuilder.set(aggregateAttributeName, 0);
					final SimpleFeature outputFeature = featureBuilder.buildFeature(feature.getID());
					featureMap.put(hash, outputFeature);
				}
				
				final SimpleFeature outputFeature = featureMap.get(hash);
				int counter = (Integer)outputFeature.getAttribute(aggregateAttributeName) + 1;
				outputFeature.setAttribute(aggregateAttributeName, counter);
				
				if (counter > this.lastStatistics.numMaxEntriesInAggregate) {
					this.lastStatistics.numMaxEntriesInAggregate = counter;
				}
			}
		} finally {
			featureIt.close(); // closes the underlying database query, ...  
		}
		
		if (featureIt.isMeasuringEnabled()) {
			LOGGER.info("Spend "+featureIt.getTimeSpendInSeconds()+" seconds on just reading "
					+ collection.size()
					+ " features from the datasource.");
		}
		if (hasher.isMeasuringEnabled()) {
			LOGGER.info("Spend "+hasher.getTimeSpendInSeconds()+" seconds on just creating feature hashes.");
		}
		
		// build the result collection
		final ListFeatureCollection result = new ListFeatureCollection(outputSchema);
		result.addAll(featureMap.values());
		this.lastStatistics.numAggregates = result.size();
		
		if (this.measuringEnabled) {
			LOGGER.info("Aggregated "+collection.size()+" incoming features to "
						+result.size()+" outgoing features");
		}
		return result;
	}

	/**
	 * build the new featuretype for the output geometries
	 * 
	 * @param inputSchema
	 * @param attributesSet
	 * @return
	 */
	protected SimpleFeatureType buildOutputFeatureType(final SimpleFeatureType inputSchema, final HashSet<String> attributesSet, final String aggregateAttributeName) {
		
		final SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
		typeBuilder.setName("aggregate");
		typeBuilder.setCRS(inputSchema.getCoordinateReferenceSystem());
		
		final GeometryDescriptor geomDescriptor = inputSchema.getGeometryDescriptor();
		typeBuilder.add(geomDescriptor.getName().toString(), 
				geomDescriptor.getType().getClass(), 
				inputSchema.getCoordinateReferenceSystem());

		Iterator<String> attributesSetIt = attributesSet.iterator();
		while(attributesSetIt.hasNext()) {
			final String attributeName =  attributesSetIt.next();
			AttributeDescriptor descriptor = inputSchema.getDescriptor(attributeName);
			LOGGER.finer("Adding attribute "+attributeName+" to new SimpleFeatureType");
			typeBuilder.add(attributeName, descriptor.getType().getClass());
		}

		// column to store the counts
		LOGGER.finer("Adding attribute "+aggregateAttributeName+" to new SimpleFeatureType");
		typeBuilder.add(aggregateAttributeName, Integer.class);
		
		return typeBuilder.buildFeatureType();
	}
	
	/**
	 * get the statistics of the last aggregation performed
	 * 
	 * @return
	 */
	public AggregationStatistics getAggregationStatistics() {
		return this.lastStatistics;
	}
}
