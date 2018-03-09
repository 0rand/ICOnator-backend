package io.iconator.commons.sql.dao;

import io.iconator.commons.model.db.KeyPairs;
import io.iconator.commons.sql.dao.config.TestConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {TestConfig.class})
@DataJpaTest
@Transactional
public class KeyPairsRepositoryTest {

    @Autowired
    private KeyPairsRepository keyPairsRepository;

    @Test
    public void testGetFresh() {
        long freshKeyID = keyPairsRepository.getFreshKeyID();
        KeyPairs kp = keyPairsRepository.findOne(freshKeyID);
        assertTrue(kp.getPublicBtc() != null);
        assertTrue(kp.getPublicEth() != null);
    }

}