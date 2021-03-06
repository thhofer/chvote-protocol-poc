/*-------------------------------------------------------------------------------------------------
 - #%L                                                                                            -
 - chvote-protocol-poc                                                                            -
 - %%                                                                                             -
 - Copyright (C) 2016 - 2017 République et Canton de Genève                                       -
 - %%                                                                                             -
 - This program is free software: you can redistribute it and/or modify                           -
 - it under the terms of the GNU Affero General Public License as published by                    -
 - the Free Software Foundation, either version 3 of the License, or                              -
 - (at your option) any later version.                                                            -
 -                                                                                                -
 - This program is distributed in the hope that it will be useful,                                -
 - but WITHOUT ANY WARRANTY; without even the implied warranty of                                 -
 - MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the                                   -
 - GNU General Public License for more details.                                                   -
 -                                                                                                -
 - You should have received a copy of the GNU Affero General Public License                       -
 - along with this program. If not, see <http://www.gnu.org/licenses/>.                           -
 - #L%                                                                                            -
 -------------------------------------------------------------------------------------------------*/

package ch.ge.ve.protopoc.service.algorithm

import ch.ge.ve.protopoc.service.model.*
import ch.ge.ve.protopoc.service.model.polynomial.Point
import ch.ge.ve.protopoc.service.support.Hash
import ch.ge.ve.protopoc.service.support.RandomGenerator
import spock.lang.Specification

import static ch.ge.ve.protopoc.service.support.BigIntegers.*
import static java.math.BigInteger.ONE
import static java.math.BigInteger.ZERO

/**
 * Tests for the Vote Casting algorithms
 */
class VoteCastingClientAlgorithmsTest extends Specification {
    Hash hash = Mock()
    def defaultAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".toCharArray() as List<Character>
    EncryptionGroup encryptionGroup = new EncryptionGroup(ELEVEN, FIVE, THREE, FOUR)
    IdentificationGroup identificationGroup = new IdentificationGroup(ELEVEN, FIVE, THREE)
    SecurityParameters securityParameters = new SecurityParameters(1, 1, 2, 0.99)
    PrimeField primeField = new PrimeField(FIVE)
    PublicParameters publicParameters = new PublicParameters(
            securityParameters, encryptionGroup, identificationGroup, primeField,
            FIVE, defaultAlphabet, FIVE, defaultAlphabet,
            defaultAlphabet, 2, defaultAlphabet, 2, 2, 5
    )
    RandomGenerator randomGenerator = Mock()
    GeneralAlgorithms generalAlgorithms = Mock()

    VoteCastingClientAlgorithms voteCastingClient

    void setup() {
        voteCastingClient = new VoteCastingClientAlgorithms(publicParameters, generalAlgorithms, randomGenerator, hash)
    }

    def "genBallot should generate a valid ballot (incl. OT query and used randomness)"() {
        given: "some known randomness"
        randomGenerator.randomInZq(_) >>> [
                ONE, // genQuery, r_1
                THREE, // genBallotProof, omega_1
                ONE // genBallotProof, omega_3
        ]
        randomGenerator.randomInGq(encryptionGroup) >> FIVE // genBallotProof, omega_2
        and: "some valid selected primes"
        generalAlgorithms.getPrimes(1) >> [THREE]
        and: "some arbitrary values for the proof challenge"
        // t_1 = g_hat ^ omega_1 mod p_hat = 3 ^ 3 mod 11 = 5
        // t_2 = omega_2 * pk ^ omega_3 mod p = 5 * 3 ^ 1 mod 11 = 4
        // t_3 = g ^ omega_3 mod p = 3 ^ 1 mod 11 = 3
        generalAlgorithms.getNIZKPChallenge(
                [ONE, NINE, THREE] as BigInteger[], // x_hat, a, b
                [FIVE, FOUR, THREE] as BigInteger[],  // t_1, t_2, t_3
                1 // tau
        ) >> ONE // c

        and: "the expected preconditions check"
        generalAlgorithms.isMember_G_q_hat(ONE) >> true
        generalAlgorithms.isMember(THREE) >> true
        generalAlgorithms.isMember(NINE) >> true
        generalAlgorithms.isInZ_q(_ as BigInteger) >> { BigInteger x -> 0 <= x && x < encryptionGroup.q }
        generalAlgorithms.isInZ_q_hat(_ as BigInteger) >> { BigInteger x -> x <= 0 && x < identificationGroup.q_hat }

        when: "generating a ballot"
        def ballotQueryAndRand = voteCastingClient.genBallot("a", [1], new EncryptionPublicKey(THREE, encryptionGroup))

        then: "x_hat has the expected value"
        // x = 5
        // x_hat = g_hat ^ x mod p_hat = 3 ^ 5 mod 11 = 1
        ballotQueryAndRand.alpha.x_hat == ONE

        and: "bold_a has the expected value"
        // m = 3
        // a_1 = q_1 * pk ^ r_1 mod p = 3 * 3 ^ 1 mod 11 = 9
        ballotQueryAndRand.alpha.bold_a == [NINE]

        and: "b has the expected value"
        // b = g ^ r_1 mod p = 3 ^ 1 mod 11 = 3
        ballotQueryAndRand.alpha.b == THREE

        and: "pi has the expected value"
        // for values of t_1 to t_3 see above
        // s_1 = omega_1 + c * x mod q_hat = 3 + 1 * 15 mod 5 = 3
        // s_2 = omega_2 * m ^ c mod p = 5 * 3 ^ 1 mod 11 = 4
        // s_3 = omega_3 + c * r mod q = 1 + 1 * 1 mod 5 = 2
        ballotQueryAndRand.alpha.pi == new NonInteractiveZKP(
                [FIVE, FOUR, THREE],
                [THREE, FOUR, TWO]
        )

        and: "the provided randomness is returned"
        ballotQueryAndRand.bold_r == [ONE]
    }

    def "getSelectedPrimes"() {
        given: "some valid selected primes"
        generalAlgorithms.getPrimes(1) >> [THREE]

        when:
        def selectedPrimes = voteCastingClient.getSelectedPrimes(Arrays.asList(1))

        then:
        selectedPrimes.size() == 1
        selectedPrimes.containsAll(THREE)
    }

    def "genQuery should generate a valid query for the ballot (incl. the randomness used)"() {
        given: "some known randomness"
        randomGenerator.randomInZq(_) >> ONE

        and: "the expected preconditions checks"
        generalAlgorithms.isMember(THREE) >> true

        when: "generating a query"
        def query = voteCastingClient.genQuery([THREE], new EncryptionPublicKey(THREE, encryptionGroup))

        then:
        // a_1 = q_1 * pk ^ r_1 mod p = 3 * 3 ^ 1 mod 11 = 9
        query.bold_a == [NINE]
        query.bold_r == [ONE]
    }

    def "genBallotProof should generate a valid proof of knowledge of the ballot"() {
        given: "some known randomness"
        randomGenerator.randomInZq(_) >> THREE >> ONE // omega_1 and omega_3
        randomGenerator.randomInGq(encryptionGroup) >> FIVE // omega_2

        and: "some arbitrary values for the proof challenge"
        // t_1 = g_hat ^ omega_1 mod p_hat = 3 ^ 3 mod 11 = 5
        // t_2 = omega_2 * pk ^ omega_3 mod p = 5 * 3 ^ 1 mod 11 = 4
        // t_3 = g ^ omega_3 mod p = 3 ^ 1 mod 11 = 3
        generalAlgorithms.getNIZKPChallenge(
                [ONE, NINE, THREE] as BigInteger[], // x_hat, a, b
                [FIVE, FOUR, THREE] as BigInteger[],  // t_1, t_2, t_3
                1 // tau
        ) >> ONE // c
        and: "the expected preconditions verifications"
        generalAlgorithms.isMember_G_q_hat(ONE) >> true
        generalAlgorithms.isMember(THREE) >> true
        generalAlgorithms.isMember(NINE) >> true
        generalAlgorithms.isInZ_q(_ as BigInteger) >> { BigInteger x -> 0 <= x && x < encryptionGroup.q }
        generalAlgorithms.isInZ_q_hat(_ as BigInteger) >> { BigInteger x -> 0 <= x && x < identificationGroup.q_hat }

        when: "generating a ballot ZKP"
        def pi = voteCastingClient.genBallotProof(ZERO, THREE, ONE, ONE, NINE, THREE,
                new EncryptionPublicKey(THREE, encryptionGroup))

        then:
        // for values of t_1 to t_3 see above
        // s_1 = omega_1 + c * x mod q_hat = 3 + 1 * 5 mod 5 = 3
        // s_2 = omega_2 * m ^ c mod p = 5 * 3 ^ 1 mod 11 = 4
        // s_3 = omega_3 + c * r mod q = 1 + 1 * 1 mod 3 = 2
        pi == new NonInteractiveZKP(
                [FIVE, FOUR, THREE],
                [THREE, FOUR, TWO]
        )
    }

    def "getPointMatrix should compute the point matrix according to spec"() {
        given:
        def b1 = [ONE]
        def c1 = [[0x01, 0x02], [0x05, 0x06], [0x0A, 0x0B]] as byte[][]
        def d1 = [THREE]
        ObliviousTransferResponse beta_1 = new ObliviousTransferResponse(b1, c1, d1)

        hash.recHash_L(ONE, ONE) >> ([0x0E, 0x0A] as byte[]) // b_i * d_j^{-r_i} mod p = 1 * 3^-0 mod 11 = 1

        def b2 = [FIVE]
        def c2 = [[0x10, 0x20], [0x50, 0x60], [0xA0, 0xB0]] as byte[][]
        def d2 = [FOUR]
        ObliviousTransferResponse beta_2 = new ObliviousTransferResponse(b2, c2, d2)
        hash.recHash_L(FIVE, ONE) >> ([0xA3, 0xB0] as byte[])
        // b_i * d_j^{-r_i} mod p = 5 * 4^-0 mod 11 = 5

        and: "the expected preconditions checks"
        generalAlgorithms.isMember(ONE) >> true
        generalAlgorithms.isMember(THREE) >> true
        generalAlgorithms.isMember(FOUR) >> true
        generalAlgorithms.isMember(FIVE) >> true
        generalAlgorithms.isInZ_q(_ as BigInteger) >> { BigInteger x -> 0 <= x && x < encryptionGroup.q }

        when:
        def pointMatrix = voteCastingClient.getPointMatrix([beta_1, beta_2], [1], [3], [ZERO])
        println pointMatrix

        then:
        pointMatrix == [
                [new Point(FOUR, ONE)], // Authority 1
                [new Point(THREE, ZERO)] // Authority 2
        ]
    }

    def "getPoints should compute the points correctly from the authority's reply"() {
        given:
        def b = [ONE]
        def c = [[0x01, 0x02], [0x05, 0x06], [0x0A, 0x0B]] as byte[][]
        def d = [THREE]
        ObliviousTransferResponse beta = new ObliviousTransferResponse(b, c, d)
        hash.recHash_L(ONE, ONE) >> ([0x0E, 0x0A] as byte[]) // b_i * d_j^{-r_i} mod p = 1 * 3^-5 mod 11 = 1

        and: "the expected preconditions checks"
        generalAlgorithms.isMember(ONE) >> true
        generalAlgorithms.isMember(THREE) >> true

        when:
        def points = voteCastingClient.getPoints(beta, [1], [3], [FIVE])

        then:
        points == [new Point(FOUR, ONE)]
    }

    def "getReturnCodes should combine the given point matrix into the verification codes for the voter"() {
        given:
        def point11 = new Point(ONE, FOUR)
        def point21 = new Point(FIVE, THREE)
        def pointMatrix = [
                [ // authority 1
                  point11 // choice 1
                ],
                [ // authority 2
                  point21 // choice 1
                ]
        ]
        hash.recHash_L(point11) >> ([0x05, 0x06] as byte[])
        hash.recHash_L(point21) >> ([0xD1, 0xCF] as byte[])

        when:
        def rc = voteCastingClient.getReturnCodes([1], pointMatrix)

        then:
        rc.size() == 1
        rc[0] == "ntj" // [0xD4, 0xC9] -> 54473
    }
}
