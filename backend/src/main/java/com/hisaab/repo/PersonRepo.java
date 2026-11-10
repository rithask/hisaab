package com.hisaab.repo;

import com.hisaab.domain.Person;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PersonRepo extends JpaRepository<Person, Long> {
    List<Person> findAllByOrderByNameAsc();
}
