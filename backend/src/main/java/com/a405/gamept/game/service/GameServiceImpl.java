package com.a405.gamept.game.service;


import com.a405.gamept.game.dto.command.RaceGetCommandDto;
import com.a405.gamept.game.dto.response.RaceGetResponseDto;
import com.a405.gamept.game.entity.Race;
import com.a405.gamept.game.entity.Story;
import com.a405.gamept.game.repository.RaceRepository;
import com.a405.gamept.game.repository.StoryRepository;
import com.a405.gamept.game.util.exception.RaceInvalidException;
import com.a405.gamept.game.util.exception.StoryNotFoundException;
import com.a405.gamept.global.error.exception.BadRequestException;
import com.a405.gamept.global.error.exception.InternalServerException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GameServiceImpl implements GameService {
    private final Validator validator;
    private final RaceRepository raceRepository;
    private final StoryRepository storyRepository;
    Set<ConstraintViolation<Object>> violations;

    @Autowired
    public GameServiceImpl(RaceRepository raceRepository, StoryRepository storyRepository) {
        this.validator = Validation.buildDefaultValidatorFactory().getValidator();
        this.raceRepository = raceRepository;
        this.storyRepository = storyRepository;
    }

    @Override
    public List<RaceGetResponseDto> getRaceList(RaceGetCommandDto raceGetCommandDto) throws BadRequestException, InternalServerException {
        Story story = storyRepository.findById(raceGetCommandDto.storyCode()).orElseThrow(() -> {
            log.error("StoryNotFoundException: { GameService 스토리 조회 실패 }");
            return new StoryNotFoundException();
        });

        List<Race> raceList = raceRepository.findAllByStory(story);
        List<RaceGetResponseDto> raceGetResponseDtoList = new ArrayList<>();

        for (Race race : raceList) {
            raceGetResponseDtoList.add(RaceGetResponseDto.from(race));

            // 유효성 검사
            violations = validator.validate(raceGetResponseDtoList.get(raceGetResponseDtoList.size() - 1));

            if (!violations.isEmpty()) {  // 유효성 검사 실패 시
                for (ConstraintViolation<Object> violation : violations) {
                    log.error("RaceInvalidException: { GameService " + violation.getMessage() + " }");
                }
                throw new RaceInvalidException();
            }
        }

        return raceGetResponseDtoList;
    }
}
