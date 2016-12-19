package ch.ge.ve.protopoc.service.simulation;

import ch.ge.ve.protopoc.arithmetic.BigIntegerArithmetic;
import ch.ge.ve.protopoc.service.algorithm.*;
import ch.ge.ve.protopoc.service.exception.InvalidDecryptionProofException;
import ch.ge.ve.protopoc.service.model.*;
import ch.ge.ve.protopoc.service.protocol.AuthorityService;
import ch.ge.ve.protopoc.service.protocol.DefaultAuthority;
import ch.ge.ve.protopoc.service.protocol.DefaultBulletinBoard;
import ch.ge.ve.protopoc.service.protocol.DefaultVotingClient;
import ch.ge.ve.protopoc.service.support.Conversion;
import ch.ge.ve.protopoc.service.support.Hash;
import ch.ge.ve.protopoc.service.support.JacobiSymbol;
import ch.ge.ve.protopoc.service.support.RandomGenerator;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ch.ge.ve.protopoc.arithmetic.BigIntegerArithmetic.modExp;
import static ch.ge.ve.protopoc.service.support.BigIntegers.TWO;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

/**
 * Simulation class...
 */
public class Simulation {
    private final static Logger log = LoggerFactory.getLogger(Simulation.class);

    private final SecureRandom secureRandom;
    private final RandomGenerator randomGenerator;
    private final PerformanceStats performanceStats = new PerformanceStats();
    private PublicParameters publicParameters;
    private ElectionSet electionSet;
    private DefaultBulletinBoard bulletinBoardService;
    private KeyEstablishmentAlgorithms keyEstablishmentAlgorithms;
    private Conversion conversion;
    private Hash hash;
    private ElectionPreparationAlgorithms electionPreparationAlgorithms;
    private VoteCastingAuthorityAlgorithms voteCastingAuthorityAlgorithms;
    private GeneralAlgorithms generalAlgorithms;
    private VoteConfirmationAuthorityAlgorithms voteConfirmationAuthorityAlgorithms;
    private CodeSheetPreparationAlgorithms codeSheetPreparationAlgorithms;
    private List<AuthorityService> authorities;
    private PrintingAuthoritySimulator printingAuthoritySimulator;
    private VoteCastingClientAlgorithms voteCastingClientAlgorithms;
    private VoteConfirmationClientAlgorithms voteConfirmationClientAlgorithms;
    private VoteConfirmationVoterAlgorithms voteConfirmationVoterAlgorithms;
    private List<VoterSimulator> voterSimulators;
    private List<Character> defaultAlphabet = getDefaultAlphabet();
    private MixingAuthorityAlgorithms mixingAuthorityAlgorithms;
    private DecryptionAuthorityAlgorithms decryptionAuthorityAlgorithms;
    private TallyingAuthoritiesAlgorithm tallyingAuthoritiesAlgorithm;
    private ElectionAdministrationSimulator electionAdministrationSimulator;

    public Simulation() throws NoSuchProviderException, NoSuchAlgorithmException {
        secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
        randomGenerator = new RandomGenerator(secureRandom);
    }

    public static void main(String[] args) throws NoSuchProviderException, NoSuchAlgorithmException, InvalidDecryptionProofException {
        log.info("Starting simulation");
        Simulation simulation = new Simulation();

        int level = 1;
        if (args.length == 1) {
            level = Integer.parseInt(args[0]);
        }

        simulation.initializeSettings(level);
        simulation.createComponents();

        simulation.run();
    }

    private void run() throws InvalidDecryptionProofException {
        log.info("publishing public parameters");
        performanceStats.start(performanceStats.publishingParameters);
        bulletinBoardService.publishPublicParameters(publicParameters);
        performanceStats.stop(performanceStats.publishingParameters);

        log.info("generating authorities keys");
        performanceStats.start(performanceStats.keyGeneration);
        // parallelStream.forEach returns early, this is a workaround so that the call returns once all items have been
        // processed
        boolean keyGenerationSuccess = authorities.parallelStream().map(aS -> {
            aS.generateKeys();
            return true;
        }).allMatch(b -> b);
        performanceStats.stop(performanceStats.keyGeneration);
        log.info("keyGenerationSuccess: " + keyGenerationSuccess);

        log.info("building public keys");
        performanceStats.start(performanceStats.publicKeyBuilding);
        boolean publicKeyBuildingSuccess = authorities.parallelStream().map(aS -> {
            aS.buildPublicKey();
            return true;
        }).allMatch(b -> b);
        performanceStats.stop(performanceStats.publicKeyBuilding);
        log.info("publicKeyBuildingSuccess: " + publicKeyBuildingSuccess);

        log.info("publishing election set");
        performanceStats.start(performanceStats.publishElectionSet);
        bulletinBoardService.publishElectionSet(electionSet);
        performanceStats.stop(performanceStats.publishElectionSet);

        log.info("generating electorate data");
        performanceStats.start(performanceStats.generatingElectoralData);
        boolean electorateDataGenerationSuccess = authorities.parallelStream().map(aS -> {
            aS.generateElectorateData();
            return true;
        }).allMatch(b -> b);
        performanceStats.stop(performanceStats.generatingElectoralData);
        log.info("electorateDataGenerationSuccess: " + electorateDataGenerationSuccess);

        log.info("building public credentials");
        performanceStats.start(performanceStats.buildPublicCredentials);
        boolean publicCredentialsBuildSuccess = authorities.parallelStream().map(aS -> {
            aS.buildPublicCredentials();
            return true;
        }).allMatch(b -> b);
        performanceStats.stop(performanceStats.buildPublicCredentials);
        log.info("publicCredentialsBuildSuccess: " + publicCredentialsBuildSuccess);

        log.info("printing code sheets");
        performanceStats.start(performanceStats.printingCodeSheets);
        printingAuthoritySimulator.print();
        performanceStats.stop(performanceStats.printingCodeSheets);

        log.info("stating the voting phase");
        performanceStats.start(performanceStats.votingPhase);
        List<List<Integer>> votes = voterSimulators.parallelStream()
                .map(VoterSimulator::vote).collect(Collectors.toList());
        performanceStats.stop(performanceStats.votingPhase);
        Map<Integer, Long> expectedVoteCounts = new HashMap<>();
        votes.forEach(l -> l.forEach(i -> expectedVoteCounts.compute(i - 1, (k, v) -> (v == null) ? 1 : v + 1)));
        List<Long> expectedTally = IntStream.range(0, electionSet.getCandidates().size())
                .mapToObj(i -> expectedVoteCounts.computeIfAbsent(i, k -> 0L)).collect(Collectors.toList());
        log.info("Expected results are: " + expectedTally);

        log.info("starting the mixing");
        performanceStats.start(performanceStats.mixing);
        authorities.get(0).startMixing();
        for (int i = 1; i < publicParameters.getS(); i++) {
            authorities.get(i).mixAgain();
        }
        performanceStats.stop(performanceStats.mixing);

        log.info("starting decryption");
        performanceStats.start(performanceStats.decryption);
        boolean decryptionSuccess = authorities.parallelStream().map(aS -> {
            aS.startPartialDecryption();
            return true;
        }).allMatch(b -> b);
        performanceStats.stop(performanceStats.decryption);
        log.info("decryptionSuccess: " + decryptionSuccess);

        log.info("tallying votes");
        performanceStats.start(performanceStats.tallying);
        List<Long> tally = electionAdministrationSimulator.getTally();
        performanceStats.stop(performanceStats.tallying);

        log.info("Tally is: " + tally);

        if (tally.equals(expectedTally)) {
            log.info("Vote simulation successful");
        } else {
            log.error("Vote simulation failed");
        }

        performanceStats.stop(performanceStats.totalSimulation);

        performanceStats.logStatSummary();
    }

    private void createComponents() {
        log.info("creating components");
        createUtilities();

        createAlgorithms();

        createServices();

        createSimulators();
        log.info("created components");
    }

    private void createSimulators() {
        log.info("creating simulators");
        printingAuthoritySimulator = new PrintingAuthoritySimulator(bulletinBoardService, codeSheetPreparationAlgorithms);
        printingAuthoritySimulator.setAuthorities(authorities);

        voterSimulators = IntStream.range(0, electionSet.getVoters().size()).mapToObj(i ->
                new VoterSimulator(i,
                        new DefaultVotingClient(bulletinBoardService,
                                keyEstablishmentAlgorithms,
                                voteCastingClientAlgorithms,
                                voteConfirmationClientAlgorithms),
                        voteConfirmationVoterAlgorithms))
                .collect(Collectors.toList());

        printingAuthoritySimulator.setVoterSimulators(voterSimulators);

        electionAdministrationSimulator = new ElectionAdministrationSimulator(electionSet.getCandidates().size(),
                bulletinBoardService, tallyingAuthoritiesAlgorithm);
        log.info("all simulators created");
    }

    private void createServices() {
        log.info("creating services");
        bulletinBoardService = new DefaultBulletinBoard();
        authorities = IntStream.range(0, publicParameters.getS()).mapToObj(i ->
                new DefaultAuthority(i, bulletinBoardService, keyEstablishmentAlgorithms, electionPreparationAlgorithms,
                        voteCastingAuthorityAlgorithms, voteConfirmationAuthorityAlgorithms, mixingAuthorityAlgorithms,
                        decryptionAuthorityAlgorithms)).collect(Collectors.toList());
        bulletinBoardService.setAuthorities(authorities);
        log.info("created all services");
    }

    private void createAlgorithms() {
        log.info("instantiating algorithms classes");
        generalAlgorithms = new GeneralAlgorithms(new JacobiSymbol(), hash, conversion, publicParameters.getEncryptionGroup());
        keyEstablishmentAlgorithms = new KeyEstablishmentAlgorithms(randomGenerator);
        electionPreparationAlgorithms = new ElectionPreparationAlgorithms(publicParameters, randomGenerator, hash);
        voteCastingAuthorityAlgorithms = new VoteCastingAuthorityAlgorithms(publicParameters, generalAlgorithms, randomGenerator, hash);
        voteConfirmationAuthorityAlgorithms = new VoteConfirmationAuthorityAlgorithms(publicParameters, generalAlgorithms, voteCastingAuthorityAlgorithms, hash);
        codeSheetPreparationAlgorithms = new CodeSheetPreparationAlgorithms(publicParameters);
        voteCastingClientAlgorithms = new VoteCastingClientAlgorithms(publicParameters, generalAlgorithms, randomGenerator, hash);
        voteConfirmationClientAlgorithms = new VoteConfirmationClientAlgorithms(publicParameters, generalAlgorithms, randomGenerator, hash);
        voteConfirmationVoterAlgorithms = new VoteConfirmationVoterAlgorithms();
        mixingAuthorityAlgorithms = new MixingAuthorityAlgorithms(publicParameters, generalAlgorithms, voteConfirmationAuthorityAlgorithms, randomGenerator);
        decryptionAuthorityAlgorithms = new DecryptionAuthorityAlgorithms(publicParameters, generalAlgorithms, mixingAuthorityAlgorithms, randomGenerator);
        tallyingAuthoritiesAlgorithm = new TallyingAuthoritiesAlgorithm(publicParameters, generalAlgorithms);
        log.info("instantiated all algorithm classes");
    }

    private void createUtilities() {
        conversion = new Conversion();
        hash = new Hash("SHA-512", "SUN", publicParameters.getSecurityParameters(), conversion);
    }

    private void initializeSettings(int level) {
        performanceStats.start(performanceStats.totalSimulation);
        log.info("Initializing settings");
        performanceStats.start(performanceStats.creatingPublicParameters);
        createPublicParameters(level);
        performanceStats.stop(performanceStats.creatingPublicParameters);
        performanceStats.start(performanceStats.creatingElectionSet);
        createElectionSet();
        performanceStats.stop(performanceStats.creatingElectionSet);
        log.info("Settings initialiazed");
    }

    private void createElectionSet() {
        DomainOfInfluence canton = new DomainOfInfluence("canton");
        DomainOfInfluence municipality1 = new DomainOfInfluence("municipality1");

        List<Voter> voters = IntStream.range(0, 100).mapToObj(i -> new Voter()).collect(Collectors.toList());
        voters.forEach(v -> v.addDomainsOfInfluence(canton));
        voters.subList(0, 10).forEach(v -> v.addDomainsOfInfluence(municipality1));

        Election cantonalVotation1 = new Election(3, 1, canton);
        Election cantonalVotation2 = new Election(3, 1, canton);
        Election municipalElection = new Election(10, 2, municipality1);

        List<Election> elections = Arrays.asList(cantonalVotation1, cantonalVotation2, municipalElection);

        List<Candidate> candidates = IntStream.range(0,
                cantonalVotation1.getNumberOfCandidates() +
                        cantonalVotation2.getNumberOfCandidates() +
                        municipalElection.getNumberOfCandidates()).mapToObj(
                i -> new Candidate(String.format("candidate %d", i))).collect(Collectors.toList());


        electionSet = new ElectionSet(voters, candidates, elections);
    }

    private void createPublicParameters(int level) {
        log.info("creating public parameters");
        switch (level) {
            case 0:
                createSecurityLevel0Parameters();
                break;
            case 1:
                createSecurityLevel1Parameters();
                break;
            case 2:
                createSecurityLevel2Parameters();
                break;
        }
        log.info("public parameters created");
    }

    private void createSecurityLevel0Parameters() {
        SecurityParameters securityParameters = new SecurityParameters(3, 3, 8, 0.9);

        EncryptionGroup encryptionGroup = new EncryptionGroup(SimulationConstants.p_RC0e, SimulationConstants.q_RC0e,
                SimulationConstants.g_RC0e, SimulationConstants.h_RC0e);
        IdentificationGroup identificationGroup = new IdentificationGroup(SimulationConstants.p_circ_RC0s,
                SimulationConstants.q_circ_RC0s, SimulationConstants.g_circ_RC0s);
        PrimeField primeField = createPrimeField(securityParameters);

        int l_m = 16 * ((int) Math.ceil(primeField.getP_prime().bitLength() / 8.0));

        publicParameters = new PublicParameters(securityParameters, encryptionGroup, identificationGroup, primeField,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 8,
                defaultAlphabet, 8,
                l_m, 4);
    }

    private void createSecurityLevel1Parameters() {
        SecurityParameters securityParameters = new SecurityParameters(80, 80, 160, 0.999);

        EncryptionGroup encryptionGroup = createEncryptionGroup(SimulationConstants.p_RC1e);
        IdentificationGroup identificationGroup = new IdentificationGroup(SimulationConstants.p_circ_RC1s,
                SimulationConstants.q_circ_RC1s, SimulationConstants.g_circ_RC1s);
        PrimeField primeField = createPrimeField(securityParameters);

        int l_m = 16 * ((int) Math.ceil(primeField.getP_prime().bitLength() / 8.0));

        publicParameters = new PublicParameters(securityParameters, encryptionGroup, identificationGroup, primeField,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 16,
                defaultAlphabet, 16,
                l_m, 4);
    }

    private void createSecurityLevel2Parameters() {
        SecurityParameters securityParameters = new SecurityParameters(112, 112, 256, 0.999);

        EncryptionGroup encryptionGroup = createEncryptionGroup(SimulationConstants.p2048);
        IdentificationGroup identificationGroup = createIdentificationGroup(SimulationConstants.p_circ_2048, securityParameters);
        PrimeField primeField = createPrimeField(securityParameters);

        int l_m = 16 * ((int) Math.ceil(primeField.getP_prime().bitLength() / 8.0));

        publicParameters = new PublicParameters(securityParameters, encryptionGroup, identificationGroup, primeField,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 2 * securityParameters.mu,
                defaultAlphabet, 16,
                defaultAlphabet, 16,
                l_m, 4);
    }

    private PrimeField createPrimeField(SecurityParameters securityParameters) {
        log.info("creating prime field");
        PrimeField primeField = null;
        while (primeField == null) {
            BigInteger p_prime = BigInteger.probablePrime(2 * securityParameters.mu, secureRandom);
            primeField = new PrimeField(p_prime);
        }
        log.info("prime field created");
        return primeField;
    }

    private IdentificationGroup createIdentificationGroup(BigInteger p_circ, SecurityParameters securityParameters) {
        log.info("creating identification group");
        IdentificationGroup identificationGroup = null;
        while (identificationGroup == null) {
            BigInteger p_circMinusOne = p_circ.subtract(ONE);

            BigInteger k = TWO;
            while (p_circMinusOne.mod(k).compareTo(ZERO) != 0) {
                k = k.add(ONE);
            }
            BigInteger q_circ = p_circMinusOne.divide(k);
            if (!q_circ.isProbablePrime(100)) {
                log.info("q_circ is not prime");
                continue;
            }
            if (q_circ.bitLength() < 2 * securityParameters.mu) {
                log.info("|q_circ| < 2*mu");
                continue;
            }

            BigInteger i = randomGenerator.randomInZq(p_circ);
            while (modExp(i, k, p_circ).compareTo(ONE) == 0) {
                i = randomGenerator.randomInZq(p_circ);
            }
            BigInteger g_circ = modExp(i, k, p_circ);

            try {
                identificationGroup = new IdentificationGroup(p_circ, q_circ, g_circ);
            } catch (IllegalArgumentException e) {
                log.warn("failed to create identification group", e);
                identificationGroup = null;
            }
        }
        log.info("created identification group");
        return identificationGroup;
    }

    private EncryptionGroup createEncryptionGroup(BigInteger p) {
        log.info("creating encryption group");
        EncryptionGroup encryptionGroup = null;

        while (encryptionGroup == null) {
            if (!p.isProbablePrime(100)) {
                log.info("p is not prime...");
                continue;
            }

            BigInteger pMinusOne = p.subtract(ONE);
            BigInteger q = pMinusOne.shiftRight(1);
            if (!q.isProbablePrime(100)) {
                log.info("q is not prime...");
            }

            BigInteger g = getGenerator(q, p, pMinusOne);

            BigInteger h = randomGenerator.randomInGq(new EncryptionGroup(p, q, g, TWO));

            try {
                encryptionGroup = new EncryptionGroup(p, q, g, h);
            } catch (IllegalArgumentException e) {
                log.warn("Encryption group creation failed", e);
                encryptionGroup = null;
            }
        }
        log.info("encryption group created: " + encryptionGroup);
        return encryptionGroup;
    }

    private BigInteger getGenerator(BigInteger q, BigInteger p, BigInteger pMinusOne) {
        log.info("creating a generator");
        BigInteger h = BigInteger.ONE;
        boolean safe = false;
        while (!safe) {
            h = randomGenerator.randomInZq(p);

            safe = modExp(h, TWO, p).compareTo(ONE) != 0
                    && modExp(h, q, p).compareTo(ONE) != 0
                    && pMinusOne.mod(h).compareTo(BigInteger.ZERO) != 0;

            BigInteger gInv = safe ? h.modInverse(p) : ONE;
            safe = safe && pMinusOne.mod(gInv).compareTo(BigInteger.ZERO) != 0;
        }
        log.info("generator created");
        return modExp(h, TWO, p);
    }

    private List<Character> getDefaultAlphabet() {
        char[] chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".toCharArray();

        List<Character> alphabet = new ArrayList<>();

        for (char c : chars) {
            alphabet.add(c);
        }

        return alphabet;
    }

    private class PerformanceStats {
        final String creatingPublicParameters = "creating public parameters";
        final String creatingElectionSet = "creating election set";
        final String publishingParameters = "publishing parameters";
        final String keyGeneration = "key generation";
        final String publicKeyBuilding = "public key building";
        final String publishElectionSet = "publish election set";
        final String generatingElectoralData = "generating electoral data";
        final String buildPublicCredentials = "build public credentials";
        final String printingCodeSheets = "printing code sheets";
        final String votingPhase = "voting phase";
        final String mixing = "mixing";
        final String decryption = "decryption";
        final String tallying = "tallying";
        final String totalSimulation = "total simulation time";

        private final Map<String, Stopwatch> stopwatches = new HashMap<>();

        void start(String name) {
            stopwatches.compute(name, (k, v) -> (v == null) ? Stopwatch.createStarted() : v.start());
        }

        void stop(String name) {
            stopwatches.compute(name, (k, v) -> (v == null) ? Stopwatch.createUnstarted() : v.stop());
        }

        private long getElapsed(String name, TimeUnit timeUnit) {
            return stopwatches.compute(name, (k, v) -> (v == null) ? Stopwatch.createUnstarted() : v).elapsed(timeUnit);
        }

        void logStatSummary() {
            List<String> elements = Arrays.asList(creatingPublicParameters, creatingElectionSet, publishingParameters,
                    keyGeneration, publicKeyBuilding, publishElectionSet, generatingElectoralData,
                    buildPublicCredentials, printingCodeSheets, votingPhase, mixing, decryption, tallying,
                    totalSimulation);
            log.info("##### Performance statistics");
            log.info("");
            log.info("- using LibGMP: " + BigIntegerArithmetic.isGmpLoaded());
            log.info("- length of p: " + publicParameters.getEncryptionGroup().getP().bitLength());
            log.info("- number of voters: " + electionSet.getVoters().size());
            List<Integer> candidateCounts = electionSet.getElections().stream()
                    .map(Election::getNumberOfCandidates).collect(Collectors.toList());
            log.info("- number of candidates per election: " + Joiner.on(", ").join(candidateCounts));
            log.info("");
            log.info(String.format("| %30s | %15s |", "Step name", "Time taken (ms)"));
            log.info(String.format("| %30s | %14s: |", Strings.repeat("-", 30), Strings.repeat("-", 14)));
            elements.forEach(k -> log.info(String.format("| %30s | %,15d |", k, getElapsed(k, TimeUnit.MILLISECONDS))));
        }
    }
}
