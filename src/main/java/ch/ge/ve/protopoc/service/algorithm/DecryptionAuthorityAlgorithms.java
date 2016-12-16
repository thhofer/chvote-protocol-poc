package ch.ge.ve.protopoc.service.algorithm;

import ch.ge.ve.protopoc.service.model.*;
import ch.ge.ve.protopoc.service.support.RandomGenerator;
import com.google.common.base.Preconditions;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static ch.ge.ve.protopoc.arithmetic.BigIntegerArithmetic.modExp;

/**
 * Algorithms related to the decryption of ballots
 */
public class DecryptionAuthorityAlgorithms {
    private final PublicParameters publicParameters;
    private final GeneralAlgorithms generalAlgorithms;
    private final MixingAuthorityAlgorithms mixingAuthorityAlgorithms;
    private final RandomGenerator randomGenerator;

    public DecryptionAuthorityAlgorithms(PublicParameters publicParameters, GeneralAlgorithms generalAlgorithms, MixingAuthorityAlgorithms mixingAuthorityAlgorithms, RandomGenerator randomGenerator) {
        this.publicParameters = publicParameters;
        this.generalAlgorithms = generalAlgorithms;
        this.mixingAuthorityAlgorithms = mixingAuthorityAlgorithms;
        this.randomGenerator = randomGenerator;
    }

    /**
     * Algorithms 5.48: checkShuffleProofs
     *
     * @param bold_pi   the shuffle proofs generated by the authorities
     * @param e_0       the original encryption
     * @param bold_E    the vector of the re-encryption lists, per authority
     * @param publicKey the public key
     * @param j         the index of this authority
     * @return true if all the proofs generated by the other authorities are valid, false otherwise
     */
    public boolean checkShuffleProofs(List<ShuffleProof> bold_pi, List<Encryption> e_0,
                                      List<List<Encryption>> bold_E, EncryptionPublicKey publicKey, int j) {
        int s = publicParameters.getS();
        int N = e_0.size();
        Preconditions.checkArgument(bold_pi.size() == s,
                "there should be as many proofs as there are authorities");
        Preconditions.checkArgument(bold_E.size() == s,
                "there should be as many lists of re-encryptions as there are authorities");
        Preconditions.checkArgument(bold_E.stream().map(List::size).allMatch(l -> l == N),
                "every re-encryption list should have length N");
        Preconditions.checkElementIndex(j, s,
                "The index of the authority should be valid with respect to the number of authorities");

        // insert e_0 at index 0, thus offsetting all indices for bold_E by 1
        List<List<Encryption>> tmp_bold_e = new ArrayList<>();
        tmp_bold_e.add(0, e_0);
        tmp_bold_e.addAll(bold_E);
        for (int i = 0; i < s; i++) {
            if (i != j) {
                if (!mixingAuthorityAlgorithms.checkShuffleProof(
                        bold_pi.get(i), tmp_bold_e.get(i), tmp_bold_e.get(i + 1), publicKey)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Algorithm 5.49: GetPartialDecryptions
     *
     * @param bold_e ElGamal encryption of the votes
     * @param sk_j   the decryption key share for authority j
     * @return the list of the partial decryptions of the provided ElGamal encryptions, using key share sk_j
     */
    public List<BigInteger> getPartialDecryptions(List<Encryption> bold_e, BigInteger sk_j) {
        BigInteger p = publicParameters.getEncryptionGroup().getP();
        return bold_e.stream().map(e_i -> modExp(e_i.getB(), sk_j, p)).collect(Collectors.toList());
    }

    /**
     * Algorithm 5.50: GenDecryptionProof
     *
     * @param sk_j         the private key share of authority j
     * @param pk_j         the public key share of authority j
     * @param bold_e       the vector of ElGamal encryptions
     * @param bold_b_prime the vector of partial ElGamal decryptions
     * @return a proof of knowledge for sk_j, satisfying <tt>b'_i = b_i ^ sk_j</tt> for all encryptions, and
     * <tt>pk_j = g ^ sk_j</tt>
     */
    public DecryptionProof genDecryptionProof(BigInteger sk_j, BigInteger pk_j, List<Encryption> bold_e,
                                              List<BigInteger> bold_b_prime) {
        BigInteger p = publicParameters.getEncryptionGroup().getP();
        BigInteger q = publicParameters.getEncryptionGroup().getQ();
        BigInteger g = publicParameters.getEncryptionGroup().getG();
        BigInteger omega = randomGenerator.randomInZq(q);

        BigInteger t_0 = modExp(g, omega, p);
        List<BigInteger> t = bold_e.stream().map(e_i -> modExp(e_i.getB(), omega, p)).collect(Collectors.toList());
        t.add(0, t_0);
        List<BigInteger> bold_b = bold_e.stream().map(Encryption::getB).collect(Collectors.toList());
        Object[] y = {pk_j, bold_b, bold_b_prime};
        BigInteger c = generalAlgorithms.getNIZKPChallenge(y, t.toArray(new BigInteger[0]), q);
        BigInteger s = omega.add(c.multiply(sk_j)).mod(q);

        return new DecryptionProof(t, s);
    }
}
