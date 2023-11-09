package com.a405.gamept.game.service;

import com.a405.gamept.game.dto.command.DeathCheckCommandDto;
import com.a405.gamept.game.dto.command.FightResultGetCommandDto;
import com.a405.gamept.game.dto.command.MonsterGetCommandDto;
import com.a405.gamept.game.dto.command.SkillSuccessCommandDto;
import com.a405.gamept.game.dto.response.FightResultGetResponseDto;
import com.a405.gamept.game.dto.response.MonsterGetResponseDto;
import com.a405.gamept.game.entity.*;
import com.a405.gamept.game.repository.*;
import com.a405.gamept.game.util.GameData;
import com.a405.gamept.game.util.enums.GameErrorMessage;
import com.a405.gamept.game.util.exception.GameException;
import com.a405.gamept.play.entity.Game;
import com.a405.gamept.play.entity.FightingEnermy;
import com.a405.gamept.play.entity.Player;
import com.a405.gamept.play.repository.GameRedisRepository;
import com.a405.gamept.play.repository.FightingEnermyRedisRepository;
import com.a405.gamept.play.repository.PlayerRedisRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FightServiceImpl implements FightService {

    private final Validator validator;
    Set<ConstraintViolation<Object>> violations;
    private final MonsterRepository monsterRepository;
    private final StoryRepository storyRepository;
    private final GameRedisRepository gameRedisRepository;
    private final PlayerRedisRepository playerRedisRepository;
    private final FightingEnermyRedisRepository fightingEnermyRedisRepository;
    private final JobRepository jobRepository;
    private final SkillRepository skillRepository;
    private final ItemRepository itemRepository;
    private final ItemStatRepository itemStatRepository;
    private final ActRepository actRepository;

    @Override
    public MonsterGetResponseDto getMonster(MonsterGetCommandDto monsterGetCommandDto) throws GameException {
        Story story = storyRepository.findById(monsterGetCommandDto.storyCode()).orElseThrow(() -> new GameException(GameErrorMessage.MONSTER_INVALID));

        /** 몬스터 레벨 난수 뽑기 **/
        int sum = 0;  // 랜덤하게 돌릴 숫자
        for(int i : GameData.monsterRate.values()) {
            sum += i;  // 각 확률 더하기
        }

        int randNum = (int) Math.floor(Math.random() * sum);  // 몬스터 레벨 확률

        sum = 0;  // 각 확률 더하기
        int monsterLevel = 0;
        for(int key : GameData.monsterRate.keySet()) {
            sum += GameData.monsterRate.get(key);  // 확률 더하기
            if(randNum < sum) {  // 확률에 해당할 경우
                monsterLevel = monsterGetCommandDto.playerLevel() + key;
                if(monsterLevel <= 0){  // 몬스터 레벨이 0 이하일 경우
                    monsterLevel = 1;  // 몬스터 레벨 1로 설정
                } else if(GameData.MONSTER_MAX_LEVEL < monsterLevel) {  // 몬스터 레벨이 10 초과일 경우
                    monsterLevel = GameData.MONSTER_MAX_LEVEL;  // 몬스터 레벨 10으로 설정
                }
                break;
            }
        }

        Monster monster = monsterRepository.findByStoryCodeAndLevel(monsterGetCommandDto.storyCode(), monsterLevel)
                .orElseThrow(()->new GameException(GameErrorMessage.MONSTER_INVALID));
        //log.info("등장 몬스터: { 레벨: " + monster.getLevel() + " 공격력 : "+ monster.getAttack()+" }");

        //몬스터 생성
        String fightingEnermyCode = monsterCreat(monster);
        MonsterGetResponseDto monsterGetResponseDto = MonsterGetResponseDto.from(monster, fightingEnermyCode);

        // 유효성 검사
        violations = validator.validate(monsterGetResponseDto);

        if (!violations.isEmpty()) {  // 유효성 검사 실패 시
            for (ConstraintViolation<Object> violation : violations) {
                throw new GameException(violation.getMessage());
            }
        }

        return monsterGetResponseDto;
    }

    public String monsterCreat(Monster monster){
        //log.info("Redis에 몬스터 생성");
        // 몬스터 임의 코드 생성
        String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        String code = new Random().ints(6, 0, CHARACTERS.length())
                .mapToObj(CHARACTERS::charAt)
                .map(Object::toString)
                .collect(Collectors.joining());

        FightingEnermy fightingEnermy = FightingEnermy.builder()
                .code(code)
                .level(monster.getLevel())
                .hp(monster.getHp())
                .attack(monster.getAttack())
                .build();

        fightingEnermyRedisRepository.save(fightingEnermy);

        return code;
    }
    @Override
    public FightResultGetResponseDto getFightResult(FightResultGetCommandDto fightResultGetCommandDto) {
        Game game = gameRedisRepository.findById(fightResultGetCommandDto.gameCode())
                .orElseThrow(()->new GameException(GameErrorMessage.GAME_NOT_FOUND));
        Player player = playerRedisRepository.findById(fightResultGetCommandDto.playerCode())
                .orElseThrow(()->new GameException(GameErrorMessage.PLAYER_NOT_FOUND));
        FightingEnermy fightingEnermy = fightingEnermyRedisRepository.findById(fightResultGetCommandDto.fightingEnermyCode())
                .orElseThrow(()->new GameException(GameErrorMessage.GAME_NOT_FOUND));

        String actCode = fightResultGetCommandDto.actCode();
        Subtask subtask = fightResultGetCommandDto.subtask();

        // 데미지를 주면 얼마나 데미지를 줬는지 몬스터의 피는 얼마나 남았는지
        // 공격 받았으면 얼마나 데미지를 받았고 피는 얼마나 남았는지
        int diceValue = game.getDiceValue();
        DeathCheckCommandDto deathCheckCommandDto = new DeathCheckCommandDto("","",0);
        if(subtask.equals(Subtask.NONE)){
            if(actCode.equals("ACT-001")){
                //기본공격
                deathCheckCommandDto = deathCheckCommandDto.from(basicAttack(player, fightingEnermy,diceValue));
            }else if(actCode.equals("ACT-004")){
                //도망가기
                deathCheckCommandDto = deathCheckCommandDto.from(runaway(player, fightingEnermy, actCode, diceValue));
            }
        }else if(subtask.equals(Subtask.SKILL)){
            String skillCode = fightResultGetCommandDto.actCode();
            if(skillCode.equals("SKILL-004") || skillCode.equals("SKILL-008") || skillCode.equals("SKILL-012") || skillCode.equals("SKILL-016")){
                //방어 스킬
                deathCheckCommandDto = deathCheckCommandDto.from(defence(player, fightingEnermy, skillCode, diceValue));
            }else{
                //공격 스킬
                deathCheckCommandDto = deathCheckCommandDto.from(skillAttack(player, fightingEnermy, skillCode, diceValue));
            }
        }else if(subtask.equals(Subtask.ITEM)){
            // 아이템 사용
            String itemCode = fightResultGetCommandDto.actCode();
            deathCheckCommandDto = deathCheckCommandDto.from(itemUse(player, fightingEnermy, itemCode, game.getStoryCode()));
        }


        if(deathCheckCommandDto.playerHp() == 0){
            gameRedisRepository.deleteById(game.getCode());
        }else{
            game = game.toBuilder()
                    .diceValue(0)
                    .build();

            gameRedisRepository.save(game);
        }
        return FightResultGetResponseDto.from(deathCheckCommandDto);
    }

    //기본공격
    public DeathCheckCommandDto basicAttack(Player player, FightingEnermy fightingEnermy, int diceValue){
        //log.info("기본 공격 시작");
        // 플레이어의 직업의 주 스탯에 따라 주사위 값과 합산한 값을 몬스터에 준다
        Job job = jobRepository.findById(player.getJobCode())
                .orElseThrow(()->new GameException(GameErrorMessage.JOB_NOT_FOUND));
        StringBuilder result = new StringBuilder();

        //메인 스탯 값 찾기
        String mainStatCode = job.getStat().getCode();
        int mainstatValue = player.getStat().get(mainStatCode);

        // 지존우석은 00 만큼의 데미지를 적에게 입혔다
        // 적의 HP : 00
        int damage = diceValue + plusPoint(mainstatValue);
        int fightingEnermyHp = Math.max(fightingEnermy.getHp() - damage, 0);
        String endYn = "N";

        result.append(player.getNickname()).append(" 은 적에게 ").append(damage).append("피해를 입혔다!!!\n");
        result.append("적의 HP : ").append(fightingEnermyHp).append("\n");

        fightingEnermy = savefightingEnermyHp(fightingEnermy, fightingEnermyHp);

        int playerHp = player.getHp();

        DeathCheckCommandDto deathCheckCommandDto = gameOverCheck(player, fightingEnermy, playerHp, fightingEnermyHp);
        result.append(deathCheckCommandDto.prompt());
        playerHp = deathCheckCommandDto.playerHp();
        endYn = deathCheckCommandDto.endYn();

        return deathCheckCommandDto.of(result.toString(), endYn, playerHp);
    }

    //도망가기
    public DeathCheckCommandDto runaway(Player player, FightingEnermy fightingEnermy, String actCode, int diceValue){
        //플레이어의 민첩 스텟에 따라 성공여부 확인
        //log.info("도망 시작");
        // 민첩에 영향을 받아 성공 여부 파악 후 진행
        StringBuilder result = new StringBuilder();
        Act act = actRepository.findById(actCode)
                .orElseThrow(()->new GameException(GameErrorMessage.ACT_NOT_FOUND));

        String statCode = act.getStat().getCode();
        int playerStat = player.getStat().get(statCode);
        int bonusPoint = plusPoint(playerStat);
        int successStd = act.getSuccessStd();

        DeathCheckCommandDto deathCheckCommandDto = null;
        if(successStd > diceValue+bonusPoint) {
            //실패
            result.append("나중에 보자!!!\n");
            result.append("적 : 그냥 지금 보자!!\n");
            result.append(player.getNickname()).append(" 은 적에게서 도망치려고 했지만 붙잡혀버렸다....\n");
            deathCheckCommandDto = gameOverCheck(player, fightingEnermy, player.getHp(), fightingEnermy.getHp());
            result.append(deathCheckCommandDto.prompt());
            deathCheckCommandDto = deathCheckCommandDto.of(result.toString(), deathCheckCommandDto.endYn(), deathCheckCommandDto.playerHp());
        }else {
            //성공
            result.append("두고 보자!!!\n");
            result.append(player.getNickname()).append(" 은 적에게서 성공적으로 도망쳤다!!!!");
            deathCheckCommandDto = new DeathCheckCommandDto(result.toString(), "Y", player.getHp());
        }

        return deathCheckCommandDto;
    }
    //스킬
    public DeathCheckCommandDto skillAttack(Player player, FightingEnermy fightingEnermy, String skillCode, int diceValue){
        //log.info("공격 스킬 사용 시작");
        // 플레이어의 스탯과 주사위 값에 따라 스킬 성공여부 파악
        Skill skill = skillRepository.findById(skillCode)
                .orElseThrow(()->new GameException(GameErrorMessage.SKILL_NOT_FOUND));
        StringBuilder result = new StringBuilder();

        //스킬 성공 여부 확인
        SkillSuccessCommandDto skillSuccessCommandDto = skillSuccessCheck(skill, player, diceValue);
        boolean extremeFlag = skillSuccessCommandDto.extremeFlag();
        boolean successFlag = skillSuccessCommandDto.successFlag();

        //추가 데미지 계산
        int bonusDamage = 0;
        if(successFlag){
            bonusDamage = (extremeFlag? GameData.SECOND_BONUS:GameData.FIRST_BONUS) * 10;
        }else{
            bonusDamage = (extremeFlag? GameData.FIRST_DEMERIT:0) * 10;
        }

        // 사용 성공 시 데미지를 준다 -> 방어의 경우 대성공일때 완전 방어
        // 실패 시 데미지를 주지 못한다. 플레이어의 피가 깍일 수 있음
        // 실패시 보너스 데미지 만큼 데미지를 받는다
        int skillDamage = skill.getDamage();
        int damage = 0;
        int fightingEnermyHp = fightingEnermy.getHp();
        int playerHp = player.getHp();

        result.append(player.getNickname()).append("가 ").append(skill.getName()).append(" 를 사용했다!!!\n");

        if(successFlag){
            if(extremeFlag) {
                result.append("대성공!!!\n");
            }else {
                result.append("성공!!!!\n");
            }

            damage = skillDamage + bonusDamage;
            fightingEnermyHp = Math.max(fightingEnermyHp - damage, 0);

            result.append(player.getNickname()).append(" 은 적에게 ").append(damage).append("피해를 입혔다!!!\n");
            result.append("적의 HP : ").append(fightingEnermyHp).append("\n");
        }else {
            if(extremeFlag) {
                result.append("대실패!!!\n");
                damage = bonusDamage;
                //log.info("플레이어 HP 전: "+playerHp);
                playerHp = Math.max(playerHp+damage, 0);

                result.append(player.getNickname()).append(" 은 대실패의 여파로 ").append(damage).append("피해를 입었다!!!\n");
                result.append("적의 HP : ").append(fightingEnermyHp).append("\n");
            }else {
                result.append("실패!!!!\n");

                result.append(player.getNickname()).append(" 은 스킬을 사용하는데 실패했다....");
                result.append("적의 HP : ").append(fightingEnermyHp).append("\n");
            }
        }

        //log.info("플레이어 HP 후: "+playerHp);
        player = savePlayerHp(player, playerHp);
        //log.info("플레이어 HP 저장 후: "+player.getHp());
        fightingEnermy = savefightingEnermyHp(fightingEnermy, fightingEnermyHp);

        DeathCheckCommandDto deathCheckCommandDto = gameOverCheck(player, fightingEnermy, playerHp, fightingEnermyHp);
        result.append(deathCheckCommandDto.prompt());
        playerHp = deathCheckCommandDto.playerHp();
        String endYn = deathCheckCommandDto.endYn();

        return new DeathCheckCommandDto(result.toString(), endYn, playerHp);
    }

    public DeathCheckCommandDto defence(Player player, FightingEnermy fightingEnermy, String skillCode, int diceValue){
        //log.info("방어 스킬 사용 시작");
        // 플레이어의 스탯과 주사위 값에 따라 스킬 성공여부 파악
        Skill skill = skillRepository.findById(skillCode)
                .orElseThrow(()->new GameException(GameErrorMessage.SKILL_NOT_FOUND));
        StringBuilder result = new StringBuilder();

        //방어 성공 여부 확인
        SkillSuccessCommandDto skillSuccessCommandDto = skillSuccessCheck(skill, player, diceValue);
        boolean extremeFlag = skillSuccessCommandDto.extremeFlag();
        boolean successFlag = skillSuccessCommandDto.successFlag();

        //추가 데미지 계산
        int bonusDefesive = 0;
        if(successFlag){
            bonusDefesive = (extremeFlag? GameData.SECOND_BONUS:GameData.FIRST_BONUS) * 10;
        }else{
            bonusDefesive = (extremeFlag? GameData.FIRST_BONUS:0) * 10;
        }

        //방어 사용
        int defesivePower = skill.getDamage();
        int damage = 0;
        int fightingEnermyHp = fightingEnermy.getHp();
        int fightingEnermyAttak = fightingEnermy.getAttack();
        int playerHp = player.getHp();

        result.append(player.getNickname()).append("가 ").append(skill.getName()).append(" 를 사용했다!!!\n");
        if (successFlag){
            if (extremeFlag){
                //대성공
                // 공격을 팅겨내서 데미지를 줌
                int defensiveDamage = defesivePower + bonusDefesive;
                fightingEnermyHp = Math.max(fightingEnermyHp-defensiveDamage, 0);

                result.append("대성공!!!\n");
                result.append(player.getNickname()).append("은/는 적의 공격을 팅겨냈다!!!\n");
                result.append("적에게 ").append(defensiveDamage).append(" 만큼의 충격을 돌려주었다!!!\n");
                result.append("적의 HP : ").append(fightingEnermyHp);
            }else {
                // 공격을 좀 막아줌
                damage = Math.max(fightingEnermyAttak - (defesivePower + bonusDefesive), 0);
                result.append("성공!!!\n");

                if(damage > 0) {
                    result.append("적에게 ").append(defesivePower + bonusDefesive)
                            .append(" 만큼의 충격을 막아냈다!!!\n");
                    result.append("적에게서 ").append(damage).append(" 만큼의 충격을 받았다.");
                }else{
                    result.append(player.getNickname()).append("은/는 적의 공격을 막았다!!!\n");
                }
                result.append("적의 HP : ").append(fightingEnermyHp);
            }
        }else {
            if (extremeFlag){
                //대성공
                // 적의 공격 + 실패 데미지
                damage = fightingEnermyAttak + bonusDefesive;
                result.append("대실패!!!\n");
                result.append(player.getNickname()).append("은/는 적의 공격을 막으려 했지만 대실패의 여파로 더욱 큰 충격을 받았다!!!\n");
                result.append("적에게 ").append(damage).append(" 만큼의 충격을 받았다!!!\n");
                result.append("적의 HP : ").append(fightingEnermyHp);
            }else{
                damage = fightingEnermyAttak;
                result.append("실패!!!\n");
                result.append(player.getNickname()).append("은/는 적의 공격을 막으려 했지만 막을 수 없었다!!!\n");
                result.append("적에게 ").append(damage).append(" 만큼의 충격을 받았다!!!\n");
                result.append("적의 HP : ").append(fightingEnermyHp);
            }
        }

        playerHp = Math.max(playerHp-damage, 0);
        String endYn = "N";

        player = savePlayerHp(player, playerHp);
        fightingEnermy = savefightingEnermyHp(fightingEnermy, fightingEnermyHp);

        if(playerHp == 0){
            //게임 오버
            result.append("HP가 0이 되어버렸다...\n");
            result.append(player.getNickname()).append(" 은 쓰러지고 말았다.\n").append(damage).append("눈앞이 깜깜해진다.....\n");
            endYn = "Y";
            deleteMonster(fightingEnermy);
            deletePlayer(player);

        }else if(fightingEnermyHp == 0){
            // 전투 종료
            result.append(player.getNickname()).append(" 은 적을 ").append(damage).append("무사히 쓰러트렸다!!!\n");
            endYn = "Y";
            result.append(getExp(player, fightingEnermy));
            deleteMonster(fightingEnermy);
        }

        return new DeathCheckCommandDto(result.toString(), endYn, playerHp);
    }

    public SkillSuccessCommandDto skillSuccessCheck(Skill skill, Player player, int diceValue){
        //log.info("스킬 성공 여부 확인");
        int successStd = skill.getSuccessStd();
        int extremePoint = skill.getExtremeStd();

        int playerStat = player.getStat().get(skill.getStat().getCode());
        int playerStd = diceValue + plusPoint(playerStat);

        boolean extremeFlag = false;
        int bonusDamage = 0;

        //대성공 대실패 여부
        if(successStd + extremePoint <= playerStd || successStd - extremePoint >= playerStd){
            extremeFlag = true;
        }

        // 성공 여부 확인
        boolean successFlag = successStd <= playerStd;

        return new SkillSuccessCommandDto(extremeFlag, successFlag);
    }

    public DeathCheckCommandDto gameOverCheck(Player player, FightingEnermy fightingEnermy, int playerHp, int fightingEnermyHp){
        //log.info("공격, 공격 받은 후 데미지 계산하기 - 게임 오버 체크");
        StringBuilder result = new StringBuilder();
        String endYn = "N";
        if(playerHp <= 0){
            //게임 오버
            result.append("HP가 0이 되어버렸다...\n");
            result.append(player.getNickname()).append(" 은 쓰러지고 말았다.\n");
            result.append("눈앞이 깜깜해진다.....\n");
            endYn = "Y";
            deletePlayer(player);
            deleteMonster(fightingEnermy);
        }else if(fightingEnermyHp == 0){
            // 전투 종료
            result.append(player.getNickname()).append(" 은 적을 무사히 쓰러트렸다!!!\n");
            endYn = "Y";
            result.append(getExp(player, fightingEnermy));
            deleteMonster(fightingEnermy);
        }else {
            //log.info("공격 받기 전 : "+player.getHp());
            DeathCheckCommandDto deathCheckCommandDto = attacked(player, fightingEnermy);
            result.append(deathCheckCommandDto.prompt());
            playerHp = deathCheckCommandDto.playerHp();
            endYn = deathCheckCommandDto.endYn();
        }

        return new DeathCheckCommandDto(result.toString(), endYn, playerHp);
    }

    public int plusPoint(int playerMainStat){
        //log.info("스탯에 따른 보너스 포인트 확인");
        // 보너스 포인트 계산
        int plusPoint = 0;

        if(playerMainStat >= GameData.THIRD_STEP){
            plusPoint = GameData.THIRD_BONUS;
        } else if(playerMainStat >= GameData.SECOND_STEP){
            plusPoint = GameData.SECOND_BONUS;
        } else if(playerMainStat >= GameData.FIRST_STEP){
            plusPoint = GameData.FIRST_BONUS;
        }

        return plusPoint;
    }

    //몬스터 공격
    public DeathCheckCommandDto attacked(Player player, FightingEnermy fightingEnermy){
        //log.info("몬스터의 공격을 받기 시작했다.");
        StringBuilder result = new StringBuilder();

        int damage = fightingEnermy.getAttack();
        int playerHp = Math.max(player.getHp() - damage, 0);
        String endYn = "N";
        result.append("적이 공격해온다!!!!\n");
        result.append(player.getNickname()).append(" 은 적에게 ").append(damage).append("의 공격을 받았다!!!\n");
        result.append("남은 체력 : ").append(playerHp).append("\n");
        //log.info("몬스터의 공격을 받은 후 : "+playerHp);
        player = savePlayerHp(player, playerHp);

        if(playerHp <= 0){
            result.append("HP가 0이 되어버렸다...\n");
            result.append(player.getNickname()).append(" 은 쓰러지고 말았다.\n").append(damage).append("눈앞이 깜깜해진다.....\n");
            endYn = "Y";
            deletePlayer(player);
            deleteMonster(fightingEnermy);
        }

        return new DeathCheckCommandDto(result.toString(), endYn, playerHp);
    }
    public Player savePlayerHp(Player player, int playerHp){
        player = player.toBuilder()
                .hp(playerHp)
                .build();
        playerRedisRepository.save(player);

        return player;
    }
    public FightingEnermy savefightingEnermyHp(FightingEnermy fightingEnermy, int fightingEnermyHp){
        fightingEnermy = fightingEnermy.toBuilder()
                .hp(fightingEnermyHp)
                .build();
        fightingEnermyRedisRepository.save(fightingEnermy);
        return fightingEnermy;
    }

    //아이템 사용
    public DeathCheckCommandDto itemUse(Player player, FightingEnermy fightingEnermy, String itemCode, String storyCode){
        // 플레이어가 가진 아이템 목록에서 아이템을 사용
        StringBuilder result = new StringBuilder();
        boolean haveItemFlag = false;
        List<String> itemCodeList = player.getItemCodeList();
        for(String code : itemCodeList){
            if(code.equals(itemCode)) {
                haveItemFlag = true;
                itemCodeList.remove(code);
                break;
            }
        }

        if(!haveItemFlag) throw new GameException(GameErrorMessage.ITEM_INVALID);

        Item item = itemRepository.findById(itemCode)
                .orElseThrow(()->new GameException(GameErrorMessage.ITEM_INVALID));

        ItemCase itemCase = item.getItemCase();
        String itemName = item.getName();
        int itemEffectValue = item.getEffectValue();
        DeathCheckCommandDto deathCheckCommandDto = null;

        //아이템 종류 별 발동
        if(itemCase.equals("STAT_UP")){
            List<ItemStat> itemStatList = itemStatRepository.findAllByItemCode(itemCode)
                    .orElseThrow(()-> new GameException(GameErrorMessage.ITEM_STAT_NOT_FOUND));

            result.append("로이더!!!로이더!!!\n");
            result.append(player.getNickname()).append(" 은/는 스탯 향상 아이템 ").append(itemName).append("을/를 사용했다!\n");
            Map<String, Integer> statUpMap = player.getStat();
            for(ItemStat itemStat : itemStatList){
                String statName = itemStat.getStat().getName();
                String statCode = itemStat.getStat().getCode();
                result.append(statName).append("이 ").append(itemEffectValue).append("만큼 상승합니다!!!!");
                statUpMap.put(statCode, statUpMap.get(statCode)+ itemEffectValue);
            }


            player = player.toBuilder()
                            .stat(statUpMap)
                                    .build();
            playerRedisRepository.save(player);
            result.append("전투가 종료까지 효과가 지속됩니다...");
            deathCheckCommandDto = gameOverCheck(player, fightingEnermy, player.getHp(), fightingEnermy.getHp());
        } else if(itemCase.equals("DAMAGE")){
            int damage = itemEffectValue;
            int bonusPoint = 0;

            List<ItemStat> itemStatList = itemStatRepository.findAllByItemCode(itemCode)
                    .orElseThrow(()-> new GameException(GameErrorMessage.ITEM_STAT_NOT_FOUND));

            result.append("파이어 인 더 홀!!!!!!\n");
            result.append(player.getNickname()).append(" 은/는 투척 아이템 ").append(itemName).append("을/를 사용했다!\n");
            for(ItemStat itemStat : itemStatList){
                String statName = itemStat.getStat().getName();
                String statCode = itemStat.getStat().getCode();
                int playerStatFromItem = player.getStat().get(statCode);
                bonusPoint += plusPoint(playerStatFromItem);
                result.append(statCode).append("의 영향으로 ").append(itemName).append("의 위력이 ").append(plusPoint(playerStatFromItem)).append("만큼 상승합니다!!!\n");
            }
            damage += bonusPoint;
            int fightingEnermyHp = fightingEnermy.getHp() - damage;
            result.append(player.getNickname()).append(" 은/는 적에게 ").append(damage).append("의 피해를 입혔다!\n");
            result.append("적의 체력 : ").append(fightingEnermyHp);

            deathCheckCommandDto = gameOverCheck(player, fightingEnermy, player.getHp(), fightingEnermyHp);
        } else if(itemCase.equals("HEAL")){
            int playerMaxHp = player.getStat().get("STAT-001") * 10;
            int playerHp = Math.min(playerMaxHp, player.getHp() + itemEffectValue);
            result.append("회복 아이템 사용!!!\n");
            result.append(player.getNickname()).append(" 은/는 회복 아이템 ").append(itemName).append("을/를 사용했다!\n");
            result.append("HP가 ").append(itemEffectValue).append("회복되었다.\n");
            result.append("현재 체력 : ").append(playerHp).append("\n");
            deathCheckCommandDto = gameOverCheck(player, fightingEnermy, player.getHp(), fightingEnermy.getHp());
        } else if (itemCase.equals("ESCAPE")) {
            result.append("연막탄 사용!!!\n");
            result.append(player.getNickname()).append(" 은/는 ").append(itemName).append("을/를 사용했다!\n");
            result.append("적에게서 안전하게 도망쳤다...");
            deathCheckCommandDto.of(result.toString(), "Y", player.getHp());
        }

        player = player.toBuilder()
                .itemCodeList(itemCodeList)
                .build();
        playerRedisRepository.save(player);

        return deathCheckCommandDto;
    }

    //경험치 획득 처리
    public String getExp(Player player, FightingEnermy fightingEnermy){
        StringBuilder result = new StringBuilder();
        int playerLevel = player.getLevel();
        int statPoint = player.getStatPoint();
        int playerExp = player.getExp();

        if(playerLevel < 10){
            int expStd = player.getLevel() - fightingEnermy.getLevel();
            int exp = 0;
            if(expStd == 0) exp = 5;
            else if(expStd == -2) exp = 7;
            else if(expStd == -1) exp = 6;
            else if(expStd == 2) exp = 3;
            else if(expStd == 1) exp = 4;

            playerExp += exp;


            result.append(player.getNickname()).append("은/는 ").append(exp*10).append("의 경험치를 얻었다!!!!\n");
            if(playerExp >= 10){
                playerExp = playerExp - 10;
                if (playerLevel + 1 < GameData.PLAYER_MAX_LEVEL){
                    playerLevel++;
                    statPoint++;
                    result.append(player.getNickname()).append("레벨이 올랐다\n");
                    result.append(playerLevel).append("이/가 되었다.\n");
                    result.append("스탯 포인트가 생겼다!!!!\n");
                    result.append("잔여 스탯 포인트 : ").append(statPoint).append("\n");
                }
            }
        }else{
            result.append(player.getNickname()).append("은/는 이미 충분히 강하다!!!\n");
            result.append("더 이상 레벨이 오르지 않는다.\n");
        }

        player = player.toBuilder()
                .level(playerLevel)
                .statPoint(statPoint)
                .exp(playerExp)
                .build();
        playerRedisRepository.save(player);

        return result.toString();
    }

    public void deleteMonster(FightingEnermy fightingEnermy){
        fightingEnermyRedisRepository.deleteById(fightingEnermy.getCode());
    }

    public void deletePlayer(Player player){
        playerRedisRepository.deleteById(player.getCode());
    }
}
