package sk.therasuite.reservation.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "client")
public class Client extends BaseEntity {

  private String firstName;
  private String lastName;
  private LocalDate birthDate;
  private String nationality;
  private String email;
  private String phone;

  protected Client() {}

  public Client(
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone) {
    this.firstName = firstName;
    this.lastName = lastName;
    this.birthDate = birthDate;
    this.nationality = nationality;
    this.email = email;
    this.phone = phone;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public LocalDate getBirthDate() {
    return birthDate;
  }

  public String getNationality() {
    return nationality;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }
}
