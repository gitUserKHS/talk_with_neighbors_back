package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CompatibilityScoreService {
    private static final double INTEREST_WEIGHT = 0.65;
    private static final double DISTANCE_WEIGHT = 0.25;
    private static final double AGE_WEIGHT = 0.10;
    private static final double DEFAULT_MAX_DISTANCE_KM = 50.0;

    public int calculateScore(User currentUser, User candidate, MatchingPreferencesDto preferences) {
        Double distance = calculateDistance(currentUser, candidate);
        return calculateScore(currentUser, candidate, preferences, distance);
    }

    public int calculateScore(User currentUser, User candidate, MatchingPreferencesDto preferences, Double distanceKm) {
        double interestScore = calculateInterestScore(preferredInterests(currentUser, preferences), candidate.getInterests());
        double distanceScore = calculateDistanceScore(distanceKm, maxDistance(preferences));
        double ageScore = calculateAgeScore(currentUser, candidate, preferences);

        double weightedScore = (interestScore * INTEREST_WEIGHT)
                + (distanceScore * DISTANCE_WEIGHT)
                + (ageScore * AGE_WEIGHT);
        return (int) Math.round(Math.max(0.0, Math.min(1.0, weightedScore)) * 100);
    }

    public List<String> sharedInterests(User currentUser, User candidate) {
        Set<String> mine = normalize(currentUser.getInterests());
        if (mine.isEmpty() || candidate.getInterests() == null) {
            return List.of();
        }

        return candidate.getInterests().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> mine.contains(normalizeOne(value)))
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean isEligible(User currentUser, User candidate, MatchingPreferencesDto preferences, Double distanceKm) {
        if (currentUser == null || candidate == null || candidate.getId() == null) {
            return false;
        }
        if (currentUser.getAccountType() == UserAccountType.SYSTEM
                || candidate.getAccountType() == UserAccountType.SYSTEM) {
            return false;
        }
        if (candidate.getId().equals(currentUser.getId())) {
            return false;
        }
        if (!candidate.isProfileComplete()) {
            return false;
        }
        if (!isGenderAccepted(candidate.getGender(), preferences != null ? preferences.getGender() : null)) {
            return false;
        }
        if (!isAgeAccepted(candidate.getAge(), preferences)) {
            return false;
        }
        return distanceKm == null || distanceKm <= maxDistance(preferences);
    }

    public MatchingPreferencesDto toDto(MatchingPreferences preferences, User user) {
        MatchingPreferencesDto dto = new MatchingPreferencesDto();
        dto.setMaxDistance(preferences != null && preferences.getMaxDistance() != null
                ? preferences.getMaxDistance()
                : DEFAULT_MAX_DISTANCE_KM);
        dto.setAgeRange(new Integer[]{
                preferences != null && preferences.getMinAge() != null ? preferences.getMinAge() : 18,
                preferences != null && preferences.getMaxAge() != null ? preferences.getMaxAge() : 99
        });
        dto.setGender(preferences != null ? preferences.getPreferredGender() : null);
        dto.setInterests(preferences != null && preferences.getPreferredInterests() != null
                ? preferences.getPreferredInterests()
                : safeList(user != null ? user.getInterests() : null));
        return dto;
    }

    public Double calculateDistance(User user1, User user2) {
        if (user1 == null || user2 == null
                || user1.getLatitude() == null || user1.getLongitude() == null
                || user2.getLatitude() == null || user2.getLongitude() == null) {
            return null;
        }
        return calculateDistance(
                user1.getLatitude(),
                user1.getLongitude(),
                user2.getLatitude(),
                user2.getLongitude()
        );
    }

    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int earthRadiusKm = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private double calculateInterestScore(List<String> preferredInterests, List<String> candidateInterests) {
        Set<String> preferred = normalize(preferredInterests);
        Set<String> candidate = normalize(candidateInterests);
        if (preferred.isEmpty() || candidate.isEmpty()) {
            return preferred.isEmpty() && candidate.isEmpty() ? 0.5 : 0.0;
        }

        Set<String> intersection = new LinkedHashSet<>(preferred);
        intersection.retainAll(candidate);

        Set<String> union = new LinkedHashSet<>(preferred);
        union.addAll(candidate);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private double calculateDistanceScore(Double distanceKm, double maxDistanceKm) {
        if (distanceKm == null) {
            return 0.5;
        }
        if (maxDistanceKm <= 0) {
            return distanceKm <= 0 ? 1.0 : 0.0;
        }
        return Math.max(0.0, 1.0 - (distanceKm / maxDistanceKm));
    }

    private double calculateAgeScore(User currentUser, User candidate, MatchingPreferencesDto preferences) {
        Integer candidateAge = candidate.getAge();
        if (candidateAge == null) {
            return 0.0;
        }

        Integer[] range = preferences != null ? preferences.getAgeRange() : null;
        if (range != null && range.length >= 2 && range[0] != null && range[1] != null) {
            if (candidateAge < range[0] || candidateAge > range[1]) {
                return 0.0;
            }
            int center = (range[0] + range[1]) / 2;
            int halfWidth = Math.max(1, (range[1] - range[0]) / 2);
            return Math.max(0.0, 1.0 - ((double) Math.abs(candidateAge - center) / halfWidth));
        }

        if (currentUser.getAge() == null) {
            return 0.5;
        }
        return Math.max(0.0, 1.0 - ((double) Math.abs(currentUser.getAge() - candidateAge) / 20.0));
    }

    private boolean isGenderAccepted(String candidateGender, String preferredGender) {
        if (preferredGender == null || preferredGender.isBlank()) {
            return true;
        }
        String normalized = normalizeOne(preferredGender);
        if (normalized.equals("any") || normalized.equals("all") || normalized.equals("전체")
                || normalized.equals("무관") || normalized.equals("상관없음")) {
            return true;
        }
        return normalizeOne(candidateGender).equals(normalized);
    }

    private boolean isAgeAccepted(Integer candidateAge, MatchingPreferencesDto preferences) {
        Integer[] range = preferences != null ? preferences.getAgeRange() : null;
        if (candidateAge == null || range == null || range.length < 2 || range[0] == null || range[1] == null) {
            return true;
        }
        return candidateAge >= range[0] && candidateAge <= range[1];
    }

    private double maxDistance(MatchingPreferencesDto preferences) {
        return preferences != null && preferences.getMaxDistance() != null
                ? preferences.getMaxDistance()
                : DEFAULT_MAX_DISTANCE_KM;
    }

    private List<String> preferredInterests(User currentUser, MatchingPreferencesDto preferences) {
        if (preferences != null && preferences.getInterests() != null && !preferences.getInterests().isEmpty()) {
            return preferences.getInterests();
        }
        return safeList(currentUser != null ? currentUser.getInterests() : null);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? new ArrayList<>() : values;
    }

    private Set<String> normalize(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeOne)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeOne(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
