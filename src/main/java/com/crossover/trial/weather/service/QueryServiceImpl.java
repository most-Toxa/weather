package com.crossover.trial.weather.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.crossover.trial.weather.dto.Airport;
import com.crossover.trial.weather.dto.WeatherPoint;
import com.crossover.trial.weather.enums.WeatherPointType;
import com.crossover.trial.weather.exception.InvalidEnumValueException;
import com.google.inject.Singleton;

@Singleton
public class QueryServiceImpl implements QueryService , Serializable{

	private static final long serialVersionUID = QueryServiceImpl.class.getName().hashCode();
	
    public final static Logger LOGGER = Logger.getLogger(QueryServiceImpl.class.getName());

    /** earth radius in KM */
    public static final double R = 6372.8;
    
    public static final long DAY_LENGTH_MILIS = TimeUnit.DAYS.toMillis(1);
    
	// key: iataCode; value: Airport
	ConcurrentHashMap<String, Airport> airports = new ConcurrentHashMap<>();
	
	// key: iataCode; value: updateCounter
    public static Map<String, AtomicInteger> requestFrequency = new HashMap<String, AtomicInteger>();
    
    // key: radius; value: updateCounter
    public static Map<Double, AtomicInteger> radiusFreq = new HashMap<Double, AtomicInteger>();
	
	@Override
	public List<Airport> getAirports() {
		List<Airport> result = new LinkedList<Airport>();
		result.addAll(airports.values());
		return result;
	}

	@Override
	public Airport findAirport(String iataCode) {
		return airports.get(iataCode);
	}

	@Override
	public List<WeatherPoint> getWeather(String iata, String radiusString) {
        double radius = radiusString == null || radiusString.trim().isEmpty() ? 0 : Double.valueOf(radiusString);
        
        updateRequestFrequency(iata, radius);
        
        List<WeatherPoint> result = new ArrayList<>();
        if (radius == 0) {
        	result.addAll(airports.get(iata).getWeather());
        } else {
        	Airport ad = airports.get(iata);
        	List<Airport> airports = getAirports();
        	airports.stream().forEach(a -> {
        		if (calculateDistance(ad, a) <= radius){
                	result.addAll(a.getWeather());
                }
    		});
        }
		return result;
	}
	
	
    private void updateRequestFrequency(String iata, Double radius) {
        requestFrequency.getOrDefault(iata, new AtomicInteger(0)).incrementAndGet();
        radiusFreq.getOrDefault(radius, new AtomicInteger(0)).incrementAndGet();
    }
	
	@Override
	public Map<String, Object> getHelthStatus() {
        Map<String, Object> retval = new HashMap<>();
        List<Airport> airports = getAirports();
        
        // counts all WeatherPoints updated today
    	long count = 0;
    	for (Airport a : airports){
    		count += a.getWeather().stream().filter(wp -> wp.getLastUpdateTime() > (System.currentTimeMillis() - DAY_LENGTH_MILIS)).count();
    	}
    	retval.put("datasize", count);
    	
    	// counts getWeather requests frequency per each requested airport
        Map<String, Double> freq = new HashMap<>();
        if (requestFrequency.size() > 0){
	        for (Airport a : airports){
	        	int counter = requestFrequency.get(a.getIata()).get();
	            double frac = (double)(counter/requestFrequency.size());
	            freq.put(a.getIata(), frac);
	        }
        }
        retval.put("iata_freq", freq);
        
        // TODO understand what's the point of this radiusFreq.. hist..
        List<Integer> hist = new ArrayList<Integer>(radiusFreq.size());
        if (radiusFreq.size() > 0){
	        for (Map.Entry<Double, AtomicInteger> e : radiusFreq.entrySet()) {
	            int i = e.getKey().intValue() % 10;
	            hist.set(i, hist.get(i) + e.getValue().get());
	        }
        }
        retval.put("radius_freq", hist);
        
    	return retval;
	}

    /**
     * Haversine distance between two airports.
     *
     * @param ad1 airport 1
     * @param ad2 airport 2
     * @return the distance in KM
     */
    private double calculateDistance(Airport ad1, Airport ad2) {
    	double deltaLat = Math.toRadians(ad2.getLatitude() - ad1.getLatitude());
        double deltaLon = Math.toRadians(ad2.getLongitude() - ad1.getLongitude());
        double a =  Math.pow(Math.sin(deltaLat / 2), 2) + Math.pow(Math.sin(deltaLon / 2), 2)
                * Math.cos(ad1.getLatitude()) * Math.cos(ad2.getLatitude());
        double c = 2 * Math.asin(Math.sqrt(a));
        return R * c;
    }

	@Override
	public Airport deleteAirport(String iataCode) {
		return airports.remove(iataCode);
	}
	
	@Override
	public WeatherPoint updateWeatherPoint(String iataCode, String pointType, WeatherPoint dp) {
		Airport a = findAirport(iataCode);
		Optional<WeatherPoint> w = a.getWeather().stream().filter(wp -> wp.getTypeCode().equals(pointType)).findFirst();
		if (w.isPresent()) {
			WeatherPointType wpt = WeatherPointType.getWeatherPointType(pointType);
			WeatherPoint wp = w.get();
			wp.withCount(wpt.isMeanFilterApply(dp.getMean()) ? dp.getCount() : wp.getCount())
				.withFirst(wpt.isMeanFilterApply(dp.getMean()) ? dp.getFirst() : wp.getFirst())
				.withSecond(wpt.isMeanFilterApply(dp.getMean()) ? dp.getSecond() : wp.getSecond())
				.withThird(wpt.isMeanFilterApply(dp.getMean()) ? dp.getThird() : wp.getThird())
				.withMean(dp.getMean())
				.setLastUpdateTime(System.currentTimeMillis());
			return w.get();
		} else {
			throw new InvalidEnumValueException("pointType", pointType);
		}
	}
	
	public Airport putAirport(Airport airport){
		return airports.putIfAbsent(airport.getIata(), airport); 
	}
	
	@Override
	public Airport addAirport(String iataCode, double latitude, double longitude) {
		// TODO params checks
		return putAirport(new Airport().withIata(iataCode).withLatitude(latitude).withLongitude(longitude)); 
	}
}
